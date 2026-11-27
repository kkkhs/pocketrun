package io.github.kkkhs.pocketrun.taskhost

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

internal data class ChatToolTrace(
    val tool: String,
    val status: String,
    val summary: String,
)

internal data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val timestampMillis: Long,
    val tools: List<ChatToolTrace> = emptyList(),
    val outputTokens: Long = 0L,
)

internal data class ChatConversation(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val messages: List<ChatMessage>,
)

internal data class ConversationSnapshot(
    val conversations: List<ChatConversation>,
)

internal object ConversationCodec {
    const val MAX_CONVERSATIONS = 24
    const val MAX_MESSAGES_PER_CONVERSATION = 48
    const val MAX_TITLE_BYTES = 128
    const val MAX_MESSAGE_BYTES = 8_192
    const val MAX_TOOL_SUMMARY_BYTES = 512
    const val MAX_TOOLS_PER_MESSAGE = 16
    const val MAX_STORE_BYTES = 65_536

    private val mapper = ObjectMapper()
    private val CONV_ID = Regex("conv_[a-f0-9]{32}")
    private val MSG_ID = Regex("msg_[a-f0-9]{32}")
    private val ROLES = setOf("user", "assistant")

    fun newConversationId(): String = "conv_" + UUID.randomUUID().toString().replace("-", "")
    fun newMessageId(): String = "msg_" + UUID.randomUUID().toString().replace("-", "")

    fun validateConversationId(id: String) {
        require(id.length == 37 && CONV_ID.matches(id)) { "INVALID_CONVERSATION_ID" }
    }

    fun validateTitle(title: String): String {
        val cleaned = title.replace('\r', ' ').replace('\n', ' ').trim()
        require(cleaned.isNotEmpty() && utf8(cleaned).size <= MAX_TITLE_BYTES) { "INVALID_CONVERSATION_TITLE" }
        return cleaned
    }

    fun autoTitle(prompt: String): String {
        val collapsed = prompt.replace(Regex("\\s+"), " ").trim()
        if (collapsed.isEmpty()) return "新对话"
        val builder = StringBuilder()
        var bytes = 0
        var count = 0
        var offset = 0
        while (offset < collapsed.length && count < 28) {
            val codePoint = Character.codePointAt(collapsed, offset)
            val width = utf8CodePointLength(codePoint)
            if (bytes + width > 84) break
            builder.appendCodePoint(codePoint)
            bytes += width
            count++
            offset += Character.charCount(codePoint)
        }
        val text = builder.toString().trim()
        return if (offset < collapsed.length && text.isNotEmpty()) "$text…" else text.ifEmpty { "新对话" }
    }

    fun encode(snapshot: ConversationSnapshot): ByteArray {
        require(snapshot.conversations.size <= MAX_CONVERSATIONS) { "CONVERSATION_COUNT_BUDGET" }
        val seenConvs = HashSet<String>(snapshot.conversations.size)
        val root = mapper.createObjectNode()
        root.put("schema", 1)
        val listNode = root.putArray("conversations")
        snapshot.conversations.forEach { conv ->
            validateConversationId(conv.id)
            require(seenConvs.add(conv.id)) { "DUPLICATE_CONVERSATION_ID" }
            val title = validateTitle(conv.title)
            require(conv.createdAtMillis >= 0L && conv.updatedAtMillis >= 0L) { "INVALID_TIMESTAMP" }
            require(conv.messages.size <= MAX_MESSAGES_PER_CONVERSATION) { "CONVERSATION_MESSAGE_BUDGET" }
            val convNode = listNode.addObject()
            convNode.put("id", conv.id)
            convNode.put("title", title)
            convNode.put("createdAtMillis", conv.createdAtMillis)
            convNode.put("updatedAtMillis", conv.updatedAtMillis)
            val messagesNode = convNode.putArray("messages")
            val seenMsgs = HashSet<String>(conv.messages.size)
            conv.messages.forEach { msg ->
                require(msg.id.length == 36 && MSG_ID.matches(msg.id) && seenMsgs.add(msg.id)) { "INVALID_MESSAGE_ID" }
                require(msg.role in ROLES) { "INVALID_MESSAGE_ROLE" }
                require(utf8(msg.text).size <= MAX_MESSAGE_BYTES) { "MESSAGE_TEXT_BUDGET" }
                require(msg.timestampMillis >= 0L && msg.outputTokens >= 0L) { "INVALID_MESSAGE_META" }
                require(msg.tools.size <= MAX_TOOLS_PER_MESSAGE) { "MESSAGE_TOOLS_BUDGET" }
                val msgNode = messagesNode.addObject()
                msgNode.put("id", msg.id)
                msgNode.put("role", msg.role)
                msgNode.put("text", msg.text)
                msgNode.put("timestampMillis", msg.timestampMillis)
                msgNode.put("outputTokens", msg.outputTokens)
                val toolsNode = msgNode.putArray("tools")
                msg.tools.forEach { trace ->
                    require(utf8(trace.tool).size in 1..96 && utf8(trace.status).size in 1..32 &&
                        utf8(trace.summary).size <= MAX_TOOL_SUMMARY_BYTES) { "INVALID_TOOL_TRACE" }
                    val tNode = toolsNode.addObject()
                    tNode.put("tool", trace.tool)
                    tNode.put("status", trace.status)
                    tNode.put("summary", trace.summary)
                }
            }
        }
        return utf8(mapper.writeValueAsString(root)).also {
            require(it.size <= MAX_STORE_BYTES) { "CONVERSATION_STORE_BUDGET" }
        }
    }

    fun decode(bytes: ByteArray): ConversationSnapshot {
        require(bytes.size in 1..MAX_STORE_BYTES) { "CONVERSATION_STORE_BUDGET" }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        val root = mapper.readTree(text)
        require(root is ObjectNode && root.get("schema")?.isInt == true && root.get("schema").intValue() == 1) {
            "INVALID_CONVERSATION_SCHEMA"
        }
        val listNode = root.get("conversations") as? ArrayNode ?: throw IllegalArgumentException("INVALID_CONVERSATION_SCHEMA")
        require(listNode.size() <= MAX_CONVERSATIONS) { "CONVERSATION_COUNT_BUDGET" }
        val conversations = mutableListOf<ChatConversation>()
        for (item in listNode) {
            require(item is ObjectNode) { "INVALID_CONVERSATION_ITEM" }
            val id = reqString(item, "id")
            val title = reqString(item, "title")
            val createdAt = reqLong(item, "createdAtMillis")
            val updatedAt = reqLong(item, "updatedAtMillis")
            val msgsNode = item.get("messages") as? ArrayNode ?: throw IllegalArgumentException("INVALID_CONVERSATION_ITEM")
            val messages = mutableListOf<ChatMessage>()
            for (m in msgsNode) {
                require(m is ObjectNode) { "INVALID_MESSAGE_ITEM" }
                val toolsNode = m.get("tools") as? ArrayNode ?: throw IllegalArgumentException("INVALID_MESSAGE_ITEM")
                val tools = mutableListOf<ChatToolTrace>()
                for (t in toolsNode) {
                    require(t is ObjectNode) { "INVALID_TOOL_TRACE" }
                    tools += ChatToolTrace(reqString(t, "tool"), reqString(t, "status"), reqString(t, "summary"))
                }
                messages += ChatMessage(
                    id = reqString(m, "id"),
                    role = reqString(m, "role"),
                    text = reqString(m, "text"),
                    timestampMillis = reqLong(m, "timestampMillis"),
                    tools = tools,
                    outputTokens = reqLong(m, "outputTokens"),
                )
            }
            conversations += ChatConversation(id, title, createdAt, updatedAt, messages)
        }
        return ConversationSnapshot(conversations).also(::encode)
    }

    fun listSummariesJson(conversations: List<ChatConversation>): String {
        val array = mapper.createArrayNode()
        conversations.sortedByDescending { it.updatedAtMillis }.forEach { conv ->
            val node = array.addObject()
            node.put("id", conv.id)
            node.put("title", conv.title)
            node.put("createdAtMillis", conv.createdAtMillis)
            node.put("updatedAtMillis", conv.updatedAtMillis)
            node.put("messageCount", conv.messages.size)
            val preview = conv.messages.lastOrNull()?.text.orEmpty().replace(Regex("\\s+"), " ").trim().take(72)
            node.put("lastPreview", preview)
        }
        return mapper.writeValueAsString(array)
    }

    fun messagesJson(messages: List<ChatMessage>): String {
        val array = mapper.createArrayNode()
        messages.forEach { msg ->
            val node = array.addObject()
            node.put("id", msg.id)
            node.put("role", msg.role)
            node.put("text", msg.text)
            node.put("timestampMillis", msg.timestampMillis)
            node.put("outputTokens", msg.outputTokens)
            val tools = node.putArray("tools")
            msg.tools.forEach { trace ->
                val t = tools.addObject()
                t.put("tool", trace.tool)
                t.put("status", trace.status)
                t.put("summary", trace.summary)
            }
        }
        return mapper.writeValueAsString(array)
    }

    fun toolTracesJson(tools: List<ChatToolTrace>): String {
        val array = mapper.createArrayNode()
        tools.forEach { trace ->
            val t = array.addObject()
            t.put("tool", trace.tool)
            t.put("status", trace.status)
            t.put("summary", trace.summary)
        }
        return mapper.writeValueAsString(array)
    }

    private fun reqString(node: ObjectNode, key: String): String {
        val field: JsonNode = node.get(key) ?: throw IllegalArgumentException("MISSING_$key")
        require(field.isTextual) { "INVALID_$key" }
        return field.textValue()
    }

    private fun reqLong(node: ObjectNode, key: String): Long {
        val field: JsonNode = node.get(key) ?: throw IllegalArgumentException("MISSING_$key")
        require(field.isIntegralNumber) { "INVALID_$key" }
        return field.longValue()
    }

    private fun utf8CodePointLength(codePoint: Int): Int = when {
        codePoint <= 0x7f -> 1
        codePoint <= 0x7ff -> 2
        codePoint <= 0xffff -> 3
        else -> 4
    }

    private fun utf8(text: String): ByteArray {
        val bytes = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }
}
