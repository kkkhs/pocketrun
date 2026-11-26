package io.github.kkkhs.pocketrun.taskhost

import android.content.Context
import android.util.AtomicFile
import java.io.Closeable
import java.io.File

internal interface ConversationRepository : Closeable {
    fun list(): List<ChatConversation>
    fun get(id: String): ChatConversation?
    fun create(title: String = "新对话"): ChatConversation
    fun appendMessage(conversationId: String, message: ChatMessage, autoTitlePrompt: String? = null): ChatConversation
    fun delete(id: String): List<ChatConversation>
    fun resetUnreadable(): List<ChatConversation>
}

/** Encrypted multi-conversation persistence with isolated Keystore alias & AAD domain. */
internal class ConversationStore(
    context: Context,
    private val alias: String = ALIAS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : ConversationRepository {
    private val file = AtomicFile(File(context.noBackupFilesDir, "conversations.bin"))
    private var closed = false

    override fun list(): List<ChatConversation> = synchronized(LOCK) {
        checkOpen()
        loadOrInitLocked().conversations.sortedByDescending { it.updatedAtMillis }
    }

    override fun get(id: String): ChatConversation? = synchronized(LOCK) {
        checkOpen()
        ConversationCodec.validateConversationId(id)
        loadOrInitLocked().conversations.singleOrNull { it.id == id }
    }

    override fun create(title: String): ChatConversation = synchronized(LOCK) {
        checkOpen()
        val cleanTitle = ConversationCodec.validateTitle(title.ifBlank { "新对话" })
        val now = nowMillis()
        val created = ChatConversation(ConversationCodec.newConversationId(), cleanTitle, now, now, emptyList())
        val existing = loadOrInitLocked().conversations.toMutableList()
        existing.add(0, created)
        val trimmed = pruneToBudget(existing, protectId = created.id)
        writeLocked(ConversationSnapshot(trimmed))
        created
    }

    override fun appendMessage(
        conversationId: String,
        message: ChatMessage,
        autoTitlePrompt: String?,
    ): ChatConversation = synchronized(LOCK) {
        checkOpen()
        ConversationCodec.validateConversationId(conversationId)
        val existing = loadOrInitLocked().conversations.toMutableList()
        val index = existing.indexOfFirst { it.id == conversationId }
        require(index >= 0) { "CONVERSATION_NOT_FOUND" }
        val current = existing[index]
        val nextTitle = if (autoTitlePrompt != null && current.messages.isEmpty() && current.title == "新对话") {
            ConversationCodec.autoTitle(autoTitlePrompt)
        } else current.title
        val updatedMessages = (current.messages + message).takeLast(ConversationCodec.MAX_MESSAGES_PER_CONVERSATION)
        val updated = current.copy(
            title = nextTitle,
            updatedAtMillis = maxOf(current.updatedAtMillis, message.timestampMillis),
            messages = updatedMessages,
        )
        existing[index] = updated
        val trimmed = pruneToBudget(existing, protectId = conversationId)
        writeLocked(ConversationSnapshot(trimmed))
        trimmed.single { it.id == conversationId }
    }

    override fun delete(id: String): List<ChatConversation> = synchronized(LOCK) {
        checkOpen()
        ConversationCodec.validateConversationId(id)
        val existing = loadOrInitLocked().conversations.toMutableList()
        existing.removeAll { it.id == id }
        writeLocked(ConversationSnapshot(existing))
        existing.sortedByDescending { it.updatedAtMillis }
    }

    override fun resetUnreadable(): List<ChatConversation> = synchronized(LOCK) {
        checkOpen()
        val unreadable = try { loadLocked(); false } catch (_: IllegalStateException) { true }
        check(unreadable) { "CONVERSATION_RESET_REQUIRES_UNREADABLE_STORE" }
        EncryptedSnapshotFile.clear(file, alias)
        val empty = ConversationSnapshot(emptyList())
        writeLocked(empty)
        empty.conversations
    }

    override fun close() = synchronized(LOCK) { closed = true }

    private fun checkOpen() { check(!closed) { "CONVERSATION_STORE_CLOSED" } }

    private fun loadOrInitLocked(): ConversationSnapshot =
        loadLocked() ?: ConversationSnapshot(emptyList()).also(::writeLocked)

    private fun loadLocked(): ConversationSnapshot? {
        try {
            val plaintext = EncryptedSnapshotFile.read(file, alias, AAD, ConversationCodec.MAX_STORE_BYTES) ?: return null
            try { return ConversationCodec.decode(plaintext) }
            finally { plaintext.fill(0) }
        } catch (_: Exception) {
            throw IllegalStateException("CONVERSATION_STORE_CORRUPT")
        }
    }

    private fun writeLocked(snapshot: ConversationSnapshot) {
        val plaintext = ConversationCodec.encode(snapshot)
        try {
            EncryptedSnapshotFile.write(file, alias, AAD, plaintext, ConversationCodec.MAX_STORE_BYTES)
        } finally { plaintext.fill(0) }
    }

    companion object {
        private const val ALIAS = "pocketrun.conversations.v1"
        private val AAD = "pocketrun.conversations.v1".toByteArray(Charsets.UTF_8)
        private val LOCK = Any()

        /** Keeps total encrypted store within MAX_STORE_BYTES by pruning oldest non-active conversations or oldest messages. */
        internal fun pruneToBudget(conversations: List<ChatConversation>, protectId: String): List<ChatConversation> {
            val list = conversations.sortedByDescending { it.updatedAtMillis }.toMutableList()
            while (list.size > ConversationCodec.MAX_CONVERSATIONS) {
                val dropIndex = list.indexOfLast { it.id != protectId }.takeIf { it >= 0 } ?: break
                list.removeAt(dropIndex)
            }
            while (encodedSizeOrMax(list) > ConversationCodec.MAX_STORE_BYTES) {
                val oldestOther = list.indexOfLast { it.id != protectId }
                if (oldestOther >= 0) {
                    list.removeAt(oldestOther)
                    continue
                }
                val targetIndex = list.indexOfFirst { it.id == protectId }
                if (targetIndex < 0 || list[targetIndex].messages.size <= 1) break
                val current = list[targetIndex]
                list[targetIndex] = current.copy(messages = current.messages.drop(1))
            }
            return list
        }

        private fun encodedSizeOrMax(list: List<ChatConversation>): Int = try {
            ConversationCodec.encode(ConversationSnapshot(list)).size
        } catch (_: Exception) {
            Int.MAX_VALUE
        }
    }
}
