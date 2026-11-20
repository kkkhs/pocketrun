package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.services.async.MessageServiceAsync
import com.anthropic.core.RequestOptions
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.MessageCreateParams
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local synthetic HTTP/SSE, not a model response or remote credential test. */
class AnthropicSdkProtocolTest {
    private fun demoEvents() = listOf(
            "message_start" to """{"type":"message_start","message":{"id":"msg_demo","type":"message","role":"assistant","model":"claude-opus-5-5","content":[],"container":null,"diagnostics":null,"stop_details":null,"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":1,"output_tokens":0,"cache_creation":null,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"inference_geo":null,"server_tool_use":null,"service_tier":"standard","output_tokens_details":{"thinking_tokens":0}}}}""",
            "content_block_start" to """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"demo summary"}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-demo"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":0}""",
            "content_block_start" to """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tool_demo","name":"android_device_info","caller":{"type":"direct"},"input":{}}}""",
            "content_block_delta" to """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            "content_block_stop" to """{"type":"content_block_stop","index":1}""",
            "message_delta" to """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":4}}""",
            "message_stop" to """{"type":"message_stop"}""",
        ).joinToString("") { (event, json) -> "event: $event\ndata: $json\n\n" }

    @Test fun officialSdkAccumulatesThinkingAndToolInputAndReplaysWholeMessage() {
        LocalServer { socket -> respond(socket, 200, "Content-Type: text/event-stream\r\n", demoEvents()) }.use { server ->
            val client = client(server.port)
            try {
                val accumulator = MessageAccumulator.create()
                val stream = client.messages().createStreaming(params(), RequestOptions.builder().followRedirects(false).build())
                stream.subscribe { event -> accumulator.accumulate(event) }.onCompleteFuture().get(3, TimeUnit.SECONDS)
                val result = accumulator.message()
                assertEquals("tool_demo", result.content()[1].asToolUse().id())
                assertEquals("signed-demo", result.content()[0].asThinking().signature())
                assertTrue(result.toParam().toString().contains("signed-demo"))
                assertEquals("android.device_info", ModelToolSurface.parse(result.content()[1].asToolUse()).tool)
                stream.close()
            } finally { client.close() }
        }
    }

    @Test fun productionTransportMidStreamCancellationClosesOnlyItsResponseAndFencesLateTool() {
        val releaseOld = CountDownLatch(1)
        val oldTailAttempted = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val source = object : TaskCapabilities {
            @Volatile var starts = 0
            override fun currentDeviceInfoTarget() = DeviceInfoTarget(10001)
            override fun currentLinuxTarget(): LinuxTarget = error("not used")
            override fun currentDisplayTarget(): DisplayTarget = error("not used")
            override fun prepareDeviceInfo(expected: DeviceInfoTarget) = object : OwnedExecution {
                override fun start(completion: (ExecutionOutcome) -> Unit) { starts++ }
                override fun close() {}
            }
            override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: java.nio.file.Path): OwnedExecution = error("not used")
            override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution = error("not used")
        }
        val host = TaskHost(source)
        val task = SingleTaskContext(host)
        val agent = AgentLoop(task)
        val credential = ModelCredential(ModelSettings.DEFAULT, "demo-not-a-real-api-credential")
        try {
            LocalServer { socket ->
                try { heldResponse(socket, releaseOld) } finally { oldTailAttempted.countDown() }
            }.use { oldServer ->
                LocalServer { socket -> heldResponse(socket, releaseNew) }.use { newServer ->
                    val oldClient = ObservedClient(client(oldServer.port))
                    val newClient = ObservedClient(client(newServer.port))
                    // Only the test injects loopback clients; production still validates HTTPS settings.
                    agent.start("old stream", AnthropicModelTransport(credential, oldClient), "synthetic fixture")
                    assertTrue(oldClient.firstEvent.await(3, TimeUnit.SECONDS))
                    agent.cancel()
                    assertTrue(oldClient.responseClosed.await(1, TimeUnit.SECONDS))
                    oldClient.response!!.onCompleteFuture().handle { _, _ -> Unit }.get(1, TimeUnit.SECONDS)
                    assertTrue(oldClient.response!!.onCompleteFuture().isDone)
                    agent.start("new stream", AnthropicModelTransport(credential, newClient), "synthetic fixture")
                    assertTrue(newClient.firstEvent.await(3, TimeUnit.SECONDS))
                    assertEquals(1L, newClient.responseClosed.count)
                    releaseOld.countDown()
                    assertTrue(oldTailAttempted.await(1, TimeUnit.SECONDS))
                    assertEquals(AgentPhase.MODEL, agent.state().phase)
                    assertEquals(0, source.starts)
                    assertTrue(task.state().steps.isEmpty())
                    assertEquals(1L, newClient.responseClosed.count)
                    assertFalse(newClient.response!!.onCompleteFuture().isDone)
                    agent.cancel()
                    assertTrue(newClient.responseClosed.await(1, TimeUnit.SECONDS))
                    newClient.response!!.onCompleteFuture().handle { _, _ -> Unit }.get(1, TimeUnit.SECONDS)
                    assertTrue(newClient.response!!.onCompleteFuture().isDone)
                    assertEquals(0, source.starts)
                }
            }
        } finally {
            releaseOld.countDown(); releaseNew.countDown()
            agent.close(); task.close()
        }
    }

    @Test fun closeBeforeResponseHeadersCancelsHandshake() {
        val release = CountDownLatch(1)
        LocalServer { socket -> release.await(3, TimeUnit.SECONDS); socket.close() }.use { server ->
            val client = client(server.port)
            try {
                val stream = client.messages().createStreaming(params(), RequestOptions.builder().followRedirects(false).build())
                val complete = stream.subscribe { _ -> }.onCompleteFuture()
                assertTrue(server.received.await(3, TimeUnit.SECONDS))
                stream.close()
                try { complete.get(1, TimeUnit.SECONDS) } catch (_: java.util.concurrent.ExecutionException) { /* cancelled request */ }
                assertTrue(complete.isDone)
            } finally { release.countDown(); client.close() }
        }
    }

    @Test fun redirectsNeverForwardDemoCredential() {
        LocalServer { socket -> respond(socket, 200, "", "should never receive") }.use { destination ->
            LocalServer { socket -> respond(socket, 307,
                "Location: http://127.0.0.1:${destination.port}/redirected\r\n", "") }.use { origin ->
                val client = client(origin.port)
                try {
                    val stream = client.messages().createStreaming(params(), RequestOptions.builder().followRedirects(false).build())
                    try { stream.subscribe { _ -> }.onCompleteFuture().get(3, TimeUnit.SECONDS) }
                    catch (_: java.util.concurrent.ExecutionException) { /* expected non-success HTTP response */ }
                    assertEquals(1L, destination.received.count)
                    stream.close()
                } finally { client.close() }
            }
        }
    }

    private class ObservedClient(private val delegate: AnthropicClientAsync) : AnthropicClientAsync by delegate {
        val firstEvent = CountDownLatch(1)
        val responseClosed = CountDownLatch(1)
        @Volatile var response: AsyncStreamResponse<RawMessageStreamEvent>? = null
        override fun messages(): MessageServiceAsync = object : MessageServiceAsync by delegate.messages() {
            override fun createStreaming(params: MessageCreateParams, requestOptions: RequestOptions): AsyncStreamResponse<RawMessageStreamEvent> {
                val actual = delegate.messages().createStreaming(params, requestOptions)
                response = actual
                return object : AsyncStreamResponse<RawMessageStreamEvent> by actual {
                    override fun subscribe(handler: AsyncStreamResponse.Handler<RawMessageStreamEvent>): AsyncStreamResponse<RawMessageStreamEvent> {
                        actual.subscribe(object : AsyncStreamResponse.Handler<RawMessageStreamEvent> {
                            override fun onNext(value: RawMessageStreamEvent) {
                                handler.onNext(value)
                                firstEvent.countDown()
                            }
                            override fun onComplete(error: java.util.Optional<Throwable>) { handler.onComplete(error) }
                        })
                        return this
                    }
                    override fun close() { actual.close(); responseClosed.countDown() }
                }
            }
        }
    }

    private fun heldResponse(socket: Socket, release: CountDownLatch) {
        val events = demoEvents()
        val prefix = events.substringBefore("event: content_block_start")
        socket.getOutputStream().apply {
            write("HTTP/1.1 200 Demo\r\nContent-Type: text/event-stream\r\nContent-Length: ${events.toByteArray().size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(prefix.toByteArray()); flush()
            check(release.await(3, TimeUnit.SECONDS)) { "fixture was not released" }
            write(events.substring(prefix.length).toByteArray()); flush()
        }
    }

    private fun client(port: Int) = AnthropicOkHttpClientAsync.builder()
        .apiKey("demo-not-a-real-api-credential").baseUrl("http://127.0.0.1:$port")
        .timeout(Duration.ofSeconds(2)).maxRetries(0).openTelemetry(false).build()
    private fun params() = MessageCreateParams.builder().model("claude-opus-5-5").maxTokens(16).addUserMessage("demo").build()
    private fun respond(socket: Socket, status: Int, headers: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        socket.getOutputStream().apply {
            write("HTTP/1.1 $status Demo\r\n${headers}Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    private class LocalServer(private val action: (Socket) -> Unit) : Closeable {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port = server.localPort
        val received = CountDownLatch(1)
        private var connection: Socket? = null
        private val thread = Thread {
            try {
                server.accept().use { socket ->
                    connection = socket
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { input.read() }
                    received.countDown()
                    action(socket)
                }
            } catch (_: Exception) { /* closed fixture */ }
        }.apply { isDaemon = true; start() }
        override fun close() { connection?.close(); server.close(); thread.join(1000) }
    }
}
