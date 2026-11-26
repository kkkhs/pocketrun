package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.DirectCaller
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.ToolUseBlock
import io.github.kkkhs.pocketrun.WorkspaceSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ChatEngineTest {
    private val filesDir = Files.createTempDirectory("pocketrun-chat-engine-").toRealPath()
    private val workspaceSession = WorkspaceSession(filesDir.toFile()).also { it.prepare() }
    @Volatile private var now = 0L
    private val clock = MonotonicClock { now }
    private val store = InMemoryConversationRepository()
    private val capabilities = RealWorkspaceCapabilities(workspaceSession)
    private val host = TaskHost(capabilities, clock, NonceSource { "abcdefghijklmnop" })
    private val task = SingleTaskContext(host, clock)
    private val engine = ChatEngine(task, store, clock)

    @After
    fun tearDown() {
        engine.close()
        task.close()
        workspaceSession.close()
        Files.walk(filesDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun streamsTextAndPreservesMultiTurnConversationHistory() {
        val firstTransport = StreamingScriptedTransport()
        val started = engine.send(null, "你好，介绍一下你自己", firstTransport, "workspaceReady=true")
        val convId = checkNotNull(started.conversationId)
        val req1 = firstTransport.next()
        req1.emitDelta("你好！我是 ")
        req1.emitDelta("PocketRun。")
        await { engine.state().streamingText == "你好！我是 PocketRun。" }
        req1.reply(textMessage("你好！我是 PocketRun。", tokens = 12))
        await { engine.state().phase == ChatPhase.COMPLETED }

        val savedAfterFirst = checkNotNull(store.get(convId))
        assertEquals(2, savedAfterFirst.messages.size)
        assertEquals("你好，介绍一下你自己", savedAfterFirst.title)
        assertEquals("你好！我是 PocketRun。", savedAfterFirst.messages[1].text)
        assertEquals(12L, savedAfterFirst.messages[1].outputTokens)

        val secondTransport = StreamingScriptedTransport()
        engine.send(convId, "我上一句说了什么？", secondTransport, "workspaceReady=true")
        val req2 = secondTransport.next()
        val joinedHistory = req2.history.joinToString("\n")
        assertTrue(joinedHistory.contains("你好，介绍一下你自己"))
        assertTrue(joinedHistory.contains("你好！我是 PocketRun。"))
        assertTrue(joinedHistory.contains("我上一句说了什么？"))
        req2.reply(textMessage("你刚才让我介绍一下自己。", tokens = 10))
        await { engine.state().phase == ChatPhase.COMPLETED }
        assertEquals(4, checkNotNull(store.get(convId)).messages.size)
    }

    @Test
    fun executesAutoListAndPhoneApprovedWorkspaceWriteEndToEnd() {
        val transport = StreamingScriptedTransport()
        val started = engine.send(null, "列出文件并写 summary.md", transport, "workspaceReady=true")
        val convId = checkNotNull(started.conversationId)

        val round1 = transport.next()
        round1.reply(toolUseMessage(
            toolCall("t1", "workspace_list_files", emptyMap()),
            toolCall("t2", "workspace_write_file", mapOf("path" to "reports/summary.md", "content" to "# 汇总\n共 2 条记录\n")),
        ))

        // workspace_list_files auto-runs; workspace_write_file must pause for phone approval!
        await { engine.state().phase == ChatPhase.WAITING_APPROVAL }
        val waiting = engine.state()
        assertNotNull(waiting.pendingApproval)
        assertEquals("workspace.write_file", waiting.pendingApproval!!.tool)
        assertEquals(1, waiting.activeTools.size)
        assertEquals("workspace.list_files", waiting.activeTools[0].tool)
        assertFalse(Files.exists(workspaceSession.ensureRoot().toPath().resolve("reports/summary.md")))

        assertTrue(host.onAppUiDecision(waiting.pendingApproval!!.requestNonce, AppUiDecision.APPROVE))

        val round2 = transport.next()
        val serialized = round2.history.joinToString("\n")
        assertTrue(serialized.contains("items.csv"))
        assertTrue(serialized.contains("path=reports/summary.md"))
        assertTrue(serialized.contains("status=created"))

        round2.reply(textMessage("已为你创建 reports/summary.md。", tokens = 18))
        await { engine.state().phase == ChatPhase.COMPLETED }

        val onDisk = workspaceSession.readTextFile("reports/summary.md")
        assertEquals("# 汇总\n共 2 条记录\n", onDisk.content)
        val assistantMsg = checkNotNull(store.get(convId)).messages.last()
        assertEquals(2, assistantMsg.tools.size)
        assertEquals("workspace.write_file", assistantMsg.tools[1].tool)
    }

    @Test
    fun rejectingWorkspaceReadCancelsTurnWithoutDisclosingFile() {
        val transport = StreamingScriptedTransport()
        val started = engine.send(null, "读取 items.csv", transport, "workspaceReady=true")
        val convId = checkNotNull(started.conversationId)

        transport.next().reply(toolUseMessage(
            toolCall("r1", "workspace_read_file", mapOf("path" to "items.csv")),
        ))
        await { engine.state().phase == ChatPhase.WAITING_APPROVAL }
        val nonce = engine.state().pendingApproval!!.requestNonce
        assertTrue(host.onAppUiDecision(nonce, AppUiDecision.REJECT))
        await { engine.state().phase == ChatPhase.CANCELLED }

        assertTrue(transport.requests.isEmpty())
        val lastMsg = checkNotNull(store.get(convId)).messages.last()
        assertEquals("assistant", lastMsg.role)
        assertTrue(lastMsg.text.contains("拒绝"))
    }

    @Test
    fun workspacePathTraversalIsRejectedAtTargetValidation() {
        val root = workspaceSession.ensureRoot().toPath()
        for (bad in listOf("../secret.txt", "/etc/hosts", "./items.csv", ".pocketrun-temp.tmp", "a/b/c/d/e.txt")) {
            try {
                CanonicalChildPath.targetRegularFile(root, bad)
                fail("Expected rejection for $bad")
            } catch (_: ToolValidationException) {
                // expected
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) {
            if (System.nanoTime() >= end) fail("Timed out waiting; state=${engine.state()}")
            Thread.sleep(15)
        }
    }

    private fun textMessage(text: String, tokens: Long = 8L): Message =
        buildMessage(
            StopReason.END_TURN,
            listOf(ContentBlock.ofText(TextBlock.builder().citations(emptyList()).text(text).build())),
            tokens,
        )

    private fun toolUseMessage(vararg blocks: ContentBlock): Message =
        buildMessage(StopReason.TOOL_USE, blocks.toList(), 15L)

    private fun buildMessage(reason: StopReason, blocks: List<ContentBlock>, tokens: Long): Message =
        JsonValue.from(
            mapOf(
                "id" to "msg_test_${System.nanoTime()}",
                "type" to "message",
                "role" to "assistant",
                "model" to "claude-opus-5-5",
                "content" to blocks,
                "stop_reason" to reason.asString(),
                "stop_sequence" to null,
                "usage" to mapOf("input_tokens" to 10L, "output_tokens" to tokens),
            ),
        ).convert(Message::class.java)!!

    private fun toolCall(id: String, name: String, input: Map<String, Any>): ContentBlock =
        ContentBlock.ofToolUse(
            ToolUseBlock.builder()
                .id(id)
                .name(name)
                .caller(DirectCaller.builder().build())
                .input(JsonValue.from(input))
                .build(),
        )

    private class StreamingScriptedTransport : ModelTransport {
        data class Call(
            val history: List<MessageParam>,
            private val onDelta: (String) -> Unit,
            private val completion: (Result<Message>) -> Unit,
        ) {
            @Volatile var cancelled = false
            fun emitDelta(chunk: String) { if (!cancelled) onDelta(chunk) }
            fun reply(message: Message) = completion(Result.success(message))
        }

        val requests = LinkedBlockingQueue<Call>()

        override fun request(history: List<MessageParam>, completion: (Result<Message>) -> Unit): Closeable =
            requestStreaming(history, {}, completion)

        override fun requestStreaming(
            history: List<MessageParam>,
            onTextDelta: (String) -> Unit,
            completion: (Result<Message>) -> Unit,
        ): Closeable {
            val call = Call(history, onTextDelta, completion)
            requests += call
            return Closeable { call.cancelled = true }
        }

        fun next(): Call = checkNotNull(requests.poll(3, TimeUnit.SECONDS)) { "Expected model request" }
        override fun close() {}
    }

    private class InMemoryConversationRepository : ConversationRepository {
        private val items = LinkedHashMap<String, ChatConversation>()
        private var time = 1_000L

        override fun list(): List<ChatConversation> = items.values.sortedByDescending { it.updatedAtMillis }
        override fun get(id: String): ChatConversation? = items[id]
        override fun create(title: String): ChatConversation {
            val now = ++time
            return ChatConversation(ConversationCodec.newConversationId(), title, now, now, emptyList()).also {
                items[it.id] = it
            }
        }
        override fun appendMessage(conversationId: String, message: ChatMessage, autoTitlePrompt: String?): ChatConversation {
            val current = checkNotNull(items[conversationId])
            val title = if (autoTitlePrompt != null && current.messages.isEmpty() && current.title == "新对话") {
                ConversationCodec.autoTitle(autoTitlePrompt)
            } else current.title
            val next = current.copy(title = title, updatedAtMillis = ++time, messages = current.messages + message)
            items[conversationId] = next
            return next
        }
        override fun delete(id: String): List<ChatConversation> { items.remove(id); return list() }
        override fun resetUnreadable(): List<ChatConversation> { items.clear(); return emptyList() }
        override fun close() {}
    }

    private class RealWorkspaceCapabilities(private val session: WorkspaceSession) : TaskCapabilities {
        override fun currentDeviceInfoTarget() = DeviceInfoTarget(10150)
        override fun currentLinuxTarget(): LinuxTarget = error("unused")
        override fun currentDisplayTarget(): DisplayTarget = error("unused")
        override fun currentWorkspaceRoot() = WorkspaceRootTarget(10150, session.ensureRoot().canonicalFile.toPath())

        override fun prepareDeviceInfo(expected: DeviceInfoTarget) = immediate {
            ToolResult(mapOf("androidVersion" to "16", "apiLevel" to "36", "supportedAbis" to "arm64-v8a"))
        }
        override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: Path): OwnedExecution = error("unused")
        override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution = error("unused")

        override fun prepareWorkspaceList(expected: WorkspaceRootTarget) = immediate {
            val files = session.listFiles()
            ToolResult(mapOf(
                "count" to files.size.toString(),
                "files" to files.joinToString("\n") { "${it.relativePath} (${it.bytes} B)" },
            ))
        }
        override fun prepareWorkspaceRead(expected: WorkspaceRootTarget, relativePath: String) = immediate {
            val read = session.readTextFile(relativePath)
            ToolResult(mapOf("path" to read.relativePath, "content" to read.content, "bytes" to read.bytes.toString(), "sha256" to read.sha256))
        }
        override fun prepareWorkspaceWrite(expected: WorkspaceRootTarget, relativePath: String, content: String) = immediate {
            val written = session.writeTextFile(relativePath, content)
            ToolResult(mapOf("path" to written.relativePath, "bytes" to written.bytes.toString(), "sha256" to written.sha256,
                "status" to if (written.created) "created" else "updated"))
        }

        private fun immediate(run: () -> ToolResult) = object : OwnedExecution {
            override fun start(completion: (ExecutionOutcome) -> Unit) {
                completion(try { ExecutionOutcome.Success(run()) } catch (e: Exception) {
                    ExecutionOutcome.Failure("WORKSPACE_ERROR", e.message.orEmpty())
                })
            }
            override fun close() {}
        }
    }
}
