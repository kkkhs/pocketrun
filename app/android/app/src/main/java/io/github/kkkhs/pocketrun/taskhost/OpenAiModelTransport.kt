package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal object ModelTransports {
    fun create(credential: ModelCredential, mode: ModelMode): ModelTransport = when (credential.settings.protocol) {
        ModelProtocol.ANTHROPIC -> AnthropicModelTransport(credential, mode)
        ModelProtocol.OPENAI -> OpenAiModelTransport(credential, mode)
    }
}

/**
 * Streaming OpenAI Chat Completions transport (`/v1/chat/completions`) adapting into PocketRun's
 * unified ModelTransport interface so ChatEngine & TaskHost work identically across OpenAI-compatible
 * providers (OpenAI, DeepSeek, Ark/Doubao, OpenRouter, Gemini OpenAI endpoint, etc.).
 */
internal class OpenAiModelTransport(
    private val credential: ModelCredential,
    private val mode: ModelMode = ModelMode.CHAT,
    private val client: OkHttpClient = buildClient(),
    private val endpointOverride: String? = null,
) : ModelTransport {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "PocketRun-OpenAiTransport") }
    private val closed = AtomicBoolean(false)
    private var active: PendingCall? = null

    override fun request(history: List<MessageParam>, completion: (Result<Message>) -> Unit): Closeable =
        requestStreaming(history, {}, completion)

    override fun requestStreaming(
        history: List<MessageParam>,
        onTextDelta: (String) -> Unit,
        completion: (Result<Message>) -> Unit,
    ): Closeable {
        check(!closed.get()) { "OPENAI_TRANSPORT_CLOSED" }
        val pending = PendingCall()
        synchronized(this) {
            check(active == null || active!!.settled.get()) { "OPENAI_TRANSPORT_BUSY" }
            active = pending
        }
        pending.future = worker.submit {
            try {
                check(!pending.cancelled.get())
                val url = endpointOverride ?: resolveChatCompletionsUrl(credential.settings.endpoint)
                val payload = buildRequestPayload(credential.settings.model, mode, history)
                val httpRequest = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${credential.apiKey}")
                    .header("Accept", "text/event-stream")
                    .post(payload.toRequestBody(JSON_MEDIA))
                    .build()
                val call = client.newCall(httpRequest)
                synchronized(pending) {
                    if (pending.cancelled.get()) call.cancel() else pending.call = call
                }
                if (pending.cancelled.get()) return@submit

                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("OPENAI_HTTP_${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("OPENAI_EMPTY_BODY")
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val message = if (contentType.contains("application/json") && !contentType.contains("event-stream")) {
                        val raw = body.string()
                        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_WIRE_BYTES) { "MODEL_RESPONSE_BUDGET" }
                        parseNonStreamingResponse(raw, credential.settings.model, onTextDelta)
                    } else {
                        parseSseStream(body.byteStream(), credential.settings.model, pending, onTextDelta)
                    }
                    pending.settled.set(true)
                    if (!pending.cancelled.get()) completion(Result.success(message))
                }
            } catch (error: Exception) {
                pending.settled.set(true)
                if (!pending.cancelled.get()) completion(Result.failure(error))
            }
        }
        return pending
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) { active?.close() }
            worker.shutdownNow()
            client.dispatcher.cancelAll()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private class PendingCall : Closeable {
        val cancelled = AtomicBoolean(false)
        val settled = AtomicBoolean(false)
        var future: Future<*>? = null
        var call: Call? = null
        override fun close() {
            cancelled.set(true)
            synchronized(this) { call?.cancel() }
            future?.cancel(true)
        }
    }

    private class ToolCallAccumulator(
        var id: String = "",
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    companion object {
        private val mapper = ObjectMapper()
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_EVENTS = 4_096
        private const val MAX_WIRE_BYTES = 512 * 1024

        private fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()

        internal fun resolveChatCompletionsUrl(baseEndpoint: String): String {
            val trimmed = baseEndpoint.trimEnd('/')
            if (trimmed.endsWith("/chat/completions")) return trimmed
            val path = URI(trimmed).path.orEmpty().trimEnd('/')
            val hasVersion = Regex("(^|/)v[0-9]+($|/)").containsMatchIn(path) || path.endsWith("/openai")
            return if (hasVersion) "$trimmed/chat/completions" else "$trimmed/v1/chat/completions"
        }

        internal fun buildRequestPayload(model: String, mode: ModelMode, history: List<MessageParam>): String {
            val root = mapper.createObjectNode()
            root.put("model", model)
            root.put("stream", true)
            root.put("max_tokens", 4096)
            root.putObject("stream_options").put("include_usage", true)

            val messages = root.putArray("messages")
            messages.addObject().put("role", "system").put("content", ModelToolSurface.prompt(mode))
            for (param in history) {
                appendMessageParam(messages, param)
            }

            val tools = ModelToolSurface.tools(mode)
            if (tools.isNotEmpty()) {
                val toolsNode = root.putArray("tools")
                for (tool in tools) {
                    val toolJson = mapper.readTree(SdkJsonBudget.json(tool))
                    val fn = toolsNode.addObject().put("type", "function").putObject("function")
                    fn.put("name", toolJson.path("name").asText())
                    fn.put("description", toolJson.path("description").asText())
                    val schema = toolJson.get("input_schema")
                    if (schema != null) fn.set<JsonNode>("parameters", schema)
                }
            }
            return mapper.writeValueAsString(root)
        }

        private fun appendMessageParam(target: ArrayNode, param: MessageParam) {
            val node = mapper.readTree(SdkJsonBudget.json(param))
            val role = node.path("role").asText("user")
            val content = node.get("content")
            if (content == null || content.isTextual) {
                target.addObject().put("role", role).put("content", content?.asText().orEmpty())
                return
            }
            if (!content.isArray) return
            if (role == "assistant") {
                val textParts = mutableListOf<String>()
                val toolCalls = mapper.createArrayNode()
                for (block in content) {
                    when (block.path("type").asText()) {
                        "text" -> textParts += block.path("text").asText()
                        "tool_use" -> {
                            val tc = toolCalls.addObject()
                            tc.put("id", block.path("id").asText())
                            tc.put("type", "function")
                            val fn = tc.putObject("function")
                            fn.put("name", block.path("name").asText())
                            val inputNode = block.get("input") ?: mapper.createObjectNode()
                            fn.put("arguments", mapper.writeValueAsString(inputNode))
                        }
                    }
                }
                val assistant = target.addObject().put("role", "assistant")
                val joined = textParts.joinToString("\n")
                if (joined.isNotEmpty()) assistant.put("content", joined) else assistant.putNull("content")
                if (toolCalls.size() > 0) assistant.set<JsonNode>("tool_calls", toolCalls)
            } else {
                val userTexts = mutableListOf<String>()
                for (block in content) {
                    when (block.path("type").asText()) {
                        "tool_result" -> {
                            val toolNode = target.addObject()
                            toolNode.put("role", "tool")
                            toolNode.put("tool_call_id", block.path("tool_use_id").asText())
                            val c = block.get("content")
                            val contentText = when {
                                c == null -> ""
                                c.isTextual -> c.asText()
                                else -> mapper.writeValueAsString(c)
                            }
                            toolNode.put("content", contentText)
                        }
                        "text" -> userTexts += block.path("text").asText()
                    }
                }
                if (userTexts.isNotEmpty()) {
                    target.addObject().put("role", "user").put("content", userTexts.joinToString("\n"))
                }
            }
        }

        private fun parseSseStream(
            stream: java.io.InputStream,
            model: String,
            pending: PendingCall,
            onTextDelta: (String) -> Unit,
        ): Message {
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            val textBuilder = StringBuilder()
            val toolMap = linkedMapOf<Int, ToolCallAccumulator>()
            var finishReason: String? = null
            var inputTokens = 0L
            var outputTokens = 0L
            var messageId = "msg_openai_${System.nanoTime()}"
            var events = 0
            var wireBytes = 0L

            while (true) {
                check(!pending.cancelled.get()) { "OPENAI_CANCELLED" }
                val line = reader.readLine() ?: break
                wireBytes += line.toByteArray(Charsets.UTF_8).size + 1
                check(wireBytes <= MAX_WIRE_BYTES) { "MODEL_RESPONSE_BUDGET" }
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty()) continue
                if (data == "[DONE]") break
                check(++events <= MAX_EVENTS) { "MODEL_RESPONSE_BUDGET" }

                val chunk = mapper.readTree(data)
                chunk.path("id").asText("").takeIf { it.isNotEmpty() }?.let { messageId = it }
                val usage = chunk.get("usage")
                if (usage != null && usage.isObject) {
                    if (usage.has("prompt_tokens")) inputTokens = usage.path("prompt_tokens").asLong(inputTokens)
                    if (usage.has("completion_tokens")) outputTokens = usage.path("completion_tokens").asLong(outputTokens)
                }
                val choices = chunk.get("choices")
                if (choices == null || !choices.isArray || choices.size() == 0) continue
                val first = choices.get(0)
                val fr = first.path("finish_reason")
                if (fr != null && fr.isTextual && fr.asText().isNotEmpty()) finishReason = fr.asText()
                val delta = first.get("delta") ?: continue

                val contentPiece = delta.get("content")
                if (contentPiece != null && contentPiece.isTextual && contentPiece.asText().isNotEmpty()) {
                    val piece = contentPiece.asText()
                    textBuilder.append(piece)
                    onTextDelta(piece)
                }

                val tcDelta = delta.get("tool_calls")
                if (tcDelta != null && tcDelta.isArray) {
                    for (item in tcDelta) {
                        val idx = item.path("index").asInt(0)
                        val acc = toolMap.getOrPut(idx) { ToolCallAccumulator() }
                        val idVal = item.path("id").asText("")
                        if (idVal.isNotEmpty()) acc.id = idVal
                        val fn = item.get("function")
                        if (fn != null && fn.isObject) {
                            val nameVal = fn.path("name").asText("")
                            if (nameVal.isNotEmpty()) acc.name = nameVal
                            val argsVal = fn.path("arguments").asText("")
                            if (argsVal.isNotEmpty()) acc.arguments.append(argsVal)
                        }
                    }
                }
            }

            return assembleMessage(messageId, model, textBuilder.toString(), toolMap.values.toList(), finishReason, inputTokens, outputTokens)
        }

        private fun parseNonStreamingResponse(
            raw: String,
            model: String,
            onTextDelta: (String) -> Unit,
        ): Message {
            val root = mapper.readTree(raw)
            val id = root.path("id").asText("msg_openai_${System.nanoTime()}")
            val usage = root.path("usage")
            val inTok = usage.path("prompt_tokens").asLong(0L)
            val outTok = usage.path("completion_tokens").asLong(0L)
            val choice = root.path("choices").get(0) ?: throw IllegalStateException("OPENAI_EMPTY_CHOICES")
            val finishReason = choice.path("finish_reason").asText("stop")
            val msg = choice.path("message")
            val text = msg.path("content").asText("")
            if (text.isNotEmpty()) onTextDelta(text)
            val tools = mutableListOf<ToolCallAccumulator>()
            val tcArray = msg.get("tool_calls")
            if (tcArray != null && tcArray.isArray) {
                for (tc in tcArray) {
                    val fn = tc.path("function")
                    tools += ToolCallAccumulator(
                        id = tc.path("id").asText(""),
                        name = fn.path("name").asText(""),
                        arguments = StringBuilder(fn.path("arguments").asText("{}")),
                    )
                }
            }
            return assembleMessage(id, model, text, tools, finishReason, inTok, outTok)
        }

        private fun assembleMessage(
            messageId: String,
            model: String,
            text: String,
            tools: List<ToolCallAccumulator>,
            finishReason: String?,
            inputTokens: Long,
            outputTokens: Long,
        ): Message {
            val blocks = mutableListOf<Map<String, Any>>()
            if (text.isNotEmpty()) {
                blocks += mapOf("type" to "text", "text" to text)
            }
            tools.forEachIndexed { idx, tc ->
                val rawArgs = tc.arguments.toString().ifBlank { "{}" }
                val parsedArgs = mapper.readTree(rawArgs)
                require(parsedArgs is ObjectNode) { "INVALID_OPENAI_TOOL_ARGUMENTS" }
                @Suppress("UNCHECKED_CAST")
                val inputMap = mapper.convertValue(parsedArgs, Map::class.java) as Map<String, Any?>
                val safeId = tc.id.ifBlank { "call_oa_$idx" }.replace(Regex("[^A-Za-z0-9_-]"), "_").take(128)
                blocks += mapOf(
                    "type" to "tool_use",
                    "id" to safeId,
                    "name" to tc.name,
                    "input" to inputMap,
                )
            }
            val stopReason = when {
                tools.isNotEmpty() || finishReason == "tool_calls" -> "tool_use"
                finishReason == "length" -> "max_tokens"
                finishReason == "content_filter" -> "refusal"
                else -> "end_turn"
            }
            val estimatedOutput = if (outputTokens > 0L) outputTokens
            else maxOf(1L, ((text.length + tools.sumOf { it.arguments.length }) / 4).toLong())
            return checkNotNull(
                JsonValue.from(
                    mapOf(
                        "id" to messageId,
                        "type" to "message",
                        "role" to "assistant",
                        "model" to model,
                        "content" to blocks,
                        "stop_reason" to stopReason,
                        "stop_sequence" to null,
                        "usage" to mapOf("input_tokens" to inputTokens, "output_tokens" to estimatedOutput),
                    ),
                ).convert(Message::class.java),
            )
        }
    }
}
