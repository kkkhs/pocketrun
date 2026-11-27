package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.client.AnthropicClientAsync
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.anthropic.core.LogLevel
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.AsyncStreamResponse
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.ThinkingConfigAdaptive
import java.io.Closeable
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

internal interface ModelTransport : Closeable {
    fun request(history: List<MessageParam>, completion: (Result<Message>) -> Unit): Closeable

    fun requestStreaming(
        history: List<MessageParam>,
        onTextDelta: (String) -> Unit,
        completion: (Result<Message>) -> Unit,
    ): Closeable = request(history, completion)
}

/** Official asynchronous SDK: a cancellable stream handle exists before the network handshake. */
internal class AnthropicModelTransport(
    private val credential: ModelCredential,
    private val client: AnthropicClientAsync,
    private val mode: ModelMode,
) : ModelTransport {
    constructor(credential: ModelCredential, client: AnthropicClientAsync, completeTask: Boolean = false) :
        this(credential, client, if (completeTask) ModelMode.COMPLETE else ModelMode.ORDINARY)

    constructor(credential: ModelCredential, completeTask: Boolean = false) :
        this(credential, buildClient(credential, if (completeTask) ModelMode.COMPLETE else ModelMode.ORDINARY), if (completeTask) ModelMode.COMPLETE else ModelMode.ORDINARY)

    constructor(credential: ModelCredential, mode: ModelMode) :
        this(credential, buildClient(credential, mode), mode)

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PocketRun-ModelTransport") }
    private val closed = AtomicBoolean(false)
    private var active: PendingRequest? = null

    override fun request(history: List<MessageParam>, completion: (Result<Message>) -> Unit): Closeable =
        requestStreaming(history, {}, completion)

    override fun requestStreaming(
        history: List<MessageParam>,
        onTextDelta: (String) -> Unit,
        completion: (Result<Message>) -> Unit,
    ): Closeable {
        check(!closed.get())
        val request = PendingRequest()
        synchronized(this) { check(active == null || active!!.settled.get()); active = request }
        request.future = worker.submit {
            try {
                check(!request.cancelled.get())
                val params = MessageCreateParams.builder().model(credential.settings.model)
                    .maxTokens(4096L).thinking(ThinkingConfigAdaptive.builder().build())
                    .system(ModelToolSurface.prompt(mode)).messages(history)
                    .apply { ModelToolSurface.tools(mode).forEach { addTool(it) } }.build()
                val stream = client.messages().createStreaming(params,
                    RequestOptions.builder().followRedirects(false).build())
                synchronized(request) {
                    if (request.cancelled.get()) stream.close() else request.stream = stream
                }
                if (request.cancelled.get()) return@submit
                val accumulator = MessageAccumulator.create()
                var wireBytes = 0L
                var events = 0
                stream.subscribe { event ->
                    check(!request.cancelled.get())
                    wireBytes += SdkJsonBudget.bytes(event)
                    check(++events <= 2048 && wireBytes <= 262_144) { "MODEL_RESPONSE_BUDGET" }
                    accumulator.accumulate(event)
                    if (event.isContentBlockDelta()) {
                        val delta = event.asContentBlockDelta().delta()
                        if (delta.isText()) onTextDelta(delta.asText().text())
                    }
                }.onCompleteFuture().whenComplete { _, error ->
                    request.settled.set(true)
                    if (!request.cancelled.get()) {
                        completion(if (error != null) Result.failure(error)
                            else runCatching { accumulator.message() })
                    }
                    stream.close()
                }
            } catch (error: Exception) {
                request.settled.set(true)
                if (!request.cancelled.get()) completion(Result.failure(error))
            }
        }
        return request
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) { active?.close() }
            client.close()
            worker.shutdownNow()
        }
    }

    private class PendingRequest : Closeable {
        val cancelled = AtomicBoolean(false)
        val settled = AtomicBoolean(false)
        var future: Future<*>? = null
        var stream: AsyncStreamResponse<RawMessageStreamEvent>? = null
        override fun close() {
            cancelled.set(true)
            synchronized(this) { stream?.close() }
            future?.cancel(true)
        }
    }

    companion object {
        private fun buildClient(credential: ModelCredential, mode: ModelMode = ModelMode.ORDINARY): AnthropicClientAsync =
            AnthropicOkHttpClientAsync.builder()
                .apiKey(credential.apiKey).baseUrl(credential.settings.endpoint)
                .timeout(Duration.ofSeconds(if (mode == ModelMode.CHAT) 60 else 20))
                .maxRetries(0).logLevel(LogLevel.OFF).openTelemetry(false).build()
    }
}
