package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
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
    @Test fun officialSdkAccumulatesThinkingAndToolInputAndReplaysWholeMessage() {
        val events = listOf(
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
        LocalServer { socket -> respond(socket, 200, "Content-Type: text/event-stream\r\n", events) }.use { server ->
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
