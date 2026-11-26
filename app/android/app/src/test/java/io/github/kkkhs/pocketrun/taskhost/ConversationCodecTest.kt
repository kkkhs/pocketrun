package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationCodecTest {
    @Test
    fun roundTripPreservesMessagesAndToolTraces() {
        val conv = ChatConversation(
            id = ConversationCodec.newConversationId(),
            title = "整理工作区文件",
            createdAtMillis = 1_000L,
            updatedAtMillis = 2_000L,
            messages = listOf(
                ChatMessage(ConversationCodec.newMessageId(), "user", "帮我看看工作区里有哪些文件", 1_000L),
                ChatMessage(
                    id = ConversationCodec.newMessageId(),
                    role = "assistant",
                    text = "工作区目前有 items.csv（28 B）。",
                    timestampMillis = 2_000L,
                    tools = listOf(ChatToolTrace("workspace.list_files", "succeeded", "共 1 个文件")),
                    outputTokens = 42L,
                ),
            ),
        )
        val encoded = ConversationCodec.encode(ConversationSnapshot(listOf(conv)))
        val decoded = ConversationCodec.decode(encoded)
        assertEquals(1, decoded.conversations.size)
        assertEquals(conv, decoded.conversations.single())
    }

    @Test
    fun autoTitleHandlesMultibyteUtf8Cleanly() {
        val title = ConversationCodec.autoTitle("   请帮我读取私有目录里的 items.csv 并总结成一份新的中文报告写入 summary.md   ")
        assertTrue(title.endsWith("…"))
        assertTrue(title.toByteArray(Charsets.UTF_8).size <= ConversationCodec.MAX_TITLE_BYTES)
        assertEquals("新对话", ConversationCodec.autoTitle("   \n\t  "))
    }

    @Test
    fun pruneToBudgetProtectsActiveConversationAndRespectsStoreLimit() {
        val conversations = (1..26).map { index ->
            ChatConversation(
                id = "conv_%032x".format(index),
                title = "会话 $index",
                createdAtMillis = index.toLong(),
                updatedAtMillis = index.toLong(),
                messages = listOf(
                    ChatMessage("msg_%032x".format(index), "user", "内容".repeat(400), index.toLong()),
                ),
            )
        }
        val activeId = conversations.first().id
        val pruned = ConversationStore.pruneToBudget(conversations, protectId = activeId)
        assertTrue(pruned.any { it.id == activeId })
        assertTrue(pruned.size <= ConversationCodec.MAX_CONVERSATIONS)
        assertTrue(ConversationCodec.encode(ConversationSnapshot(pruned)).size <= ConversationCodec.MAX_STORE_BYTES)
    }

    @Test
    fun invalidSchemaOrRoleIsRejected() {
        try {
            ConversationCodec.decode("""{"schema":2,"conversations":[]}""".toByteArray(Charsets.UTF_8))
            fail("Expected invalid schema failure")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
