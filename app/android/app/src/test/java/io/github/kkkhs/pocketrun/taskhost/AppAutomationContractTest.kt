package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.DirectCaller
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.ToolUseBlock
import io.github.kkkhs.pocketrun.DisplayInputPolicy
import io.github.kkkhs.pocketrun.UiTreeCodec
import io.github.kkkhs.pocketrun.UiTreeSummaryFormatter
import io.github.kkkhs.pocketrun.VirtualDisplaySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AppAutomationContractTest {
    @Test
    fun validatesGeneralAppToolContractsAndRejectsInvalidCoordinatesOrPackages() {
        assertEquals(ParsedAction.ListApps("set"), ToolSchema.validate(RawToolCall("app.list_apps", mapOf("query" to "set"))))
        assertEquals(ParsedAction.ListApps(null), ToolSchema.validate(RawToolCall("app.list_apps", emptyMap())))

        val open = ToolSchema.validate(RawToolCall("app.open", mapOf("package_name" to "com.android.settings")))
        assertEquals(ParsedAction.OpenApp("com.android.settings"), open)
        assertTrue(open.approvalRequired)

        val swipe = ToolSchema.validate(RawToolCall("app.swipe", mapOf("x1" to 360, "y1" to 1000, "x2" to 360, "y2" to 300)))
        assertEquals(ParsedAction.AppSwipe(360, 1000, 360, 300, 320), swipe)
        assertTrue(swipe.approvalRequired)

        val chinese = ToolSchema.validate(RawToolCall("app.input_text", mapOf("text" to "蓝牙与连接设置")))
        assertEquals(ParsedAction.AppInputText("蓝牙与连接设置"), chinese)
        assertTrue(chinese.approvalRequired)

        val key = ToolSchema.validate(RawToolCall("app.press_key", mapOf("key" to "back")))
        assertEquals(ParsedAction.AppPressKey("BACK"), key)
        assertTrue(key.approvalRequired)

        for (badPkg in listOf("settings", "com.bad;rm -rf", "../etc", "123.abc")) {
            try {
                ToolSchema.validate(RawToolCall("app.open", mapOf("package_name" to badPkg)))
                fail("Expected package rejection for $badPkg")
            } catch (e: ToolValidationException) {
                assertEquals("INVALID_PACKAGE", e.code)
            }
        }
        try {
            ToolSchema.validate(RawToolCall("app.tap", mapOf("x" to 720, "y" to 100)))
            fail("Expected out-of-bounds tap rejection")
        } catch (e: ToolValidationException) {
            assertEquals("INVALID_TAP", e.code)
        }
        try {
            ToolSchema.validate(RawToolCall("app.press_key", mapOf("key" to "POWER")))
            fail("Expected unsupported key rejection")
        } catch (e: ToolValidationException) {
            assertEquals("INVALID_KEY", e.code)
        }
    }

    @Test
    fun parsesLauncherComponentAndFormatsCompactInteractiveUiTree() {
        val rawResolve = "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\ncom.android.settings/.Settings\n"
        assertEquals(
            "com.android.settings/.Settings",
            VirtualDisplaySession.parseResolvedComponent(rawResolve, "com.android.settings"),
        )
        assertNull(VirtualDisplaySession.parseResolvedComponent(rawResolve, "com.other.app"))
        assertTrue(DisplayInputPolicy.acceptsAppText("你好世界 Hello 123"))
        assertFalse(DisplayInputPolicy.acceptsAppText(""))

        val tree = UiTreeCodec.Tree(
            displayId = 2,
            status = UiTreeCodec.Status.Available,
            truncated = false,
            nodes = listOf(
                UiTreeCodec.Node(-1, 1, 0, "android.widget.FrameLayout", "com.android.settings", "", "", "", 0, 0, 720, 1280, false, false, true, false),
                UiTreeCodec.Node(0, 1, 1, "android.widget.TextView", "com.android.settings", "android:id/title", "网络和互联网", "", 40, 200, 680, 300, true, false, true, false),
                UiTreeCodec.Node(0, 1, 1, "android.widget.EditText", "com.android.settings", "com.android.settings:id/search_bar", "", "搜索设置", 40, 80, 680, 160, true, true, true, true),
            ),
        )
        val summary = UiTreeSummaryFormatter.format(tree)
        assertEquals("com.android.settings", summary.topPackage)
        assertEquals(2, summary.elementCount)
        assertTrue(summary.text.contains("网络和互联网"))
        assertTrue(summary.text.contains("center=(360,250)"))
        assertTrue(summary.text.contains("[clickable]"))
        assertTrue(summary.text.contains("搜索设置"))
        assertTrue(summary.text.contains("id=search_bar"))
        assertTrue(summary.text.contains("[clickable,editable,focused]"))
    }

    @Test
    fun chatEngineRetriesTransientNetworkIoErrorAndCapturesAppPreviewUri() {
        val now = AtomicLong(0L)
        val clock = MonotonicClock { now.get() }
        val store = InMemoryRepo()
        val caps = FakeAppCapabilities()
        val host = TaskHost(caps, clock, NonceSource { "abcdefghijklmnop" })
        val task = SingleTaskContext(host, clock)
        val engine = ChatEngine(task, store, clock)
        try {
            val transport = RetryingScriptedTransport()
            engine.send(null, "打开系统设置并观察界面", transport, "displayReady=true; displayId=2; displayEpoch=5")

            // Simulate transient IOException on first request -> ChatEngine schedules retry instead of failing!
            val attempt1 = transport.next()
            attempt1.fail(IOException("Software caused connection abort"))
            await(engine) { engine.state().streamingText.contains("网络波动") }
            assertEquals(ChatPhase.STREAMING, engine.state().phase)

            // Advance monotonic clock past 2s backoff -> engine automatically retries.
            now.addAndGet(TimeUnit.SECONDS.toNanos(3))
            val attempt2 = transport.next()
            attempt2.reply(
                toolUseMessage(
                    toolCall("a1", "app_open", mapOf("package_name" to "com.android.settings")),
                    toolCall("a2", "app_observe", emptyMap()),
                ),
            )

            // Approve app.open
            await(engine) { engine.state().phase == ChatPhase.WAITING_APPROVAL && engine.state().pendingApproval?.tool == "app.open" }
            assertTrue(host.onAppUiDecision(engine.state().pendingApproval!!.requestNonce, AppUiDecision.APPROVE))

            // Approve app.observe
            await(engine) { engine.state().phase == ChatPhase.WAITING_APPROVAL && engine.state().pendingApproval?.tool == "app.observe" }
            assertEquals("file:///cache/open-preview.jpg", engine.state().latestPreviewUri)
            assertTrue(host.onAppUiDecision(engine.state().pendingApproval!!.requestNonce, AppUiDecision.APPROVE))

            val round2 = transport.next()
            val joined = round2.history.joinToString("\n")
            assertTrue(joined.contains("topPackage=com.android.settings"))
            assertTrue(joined.contains("网络和互联网"))
            round2.reply(textMessage("已打开系统设置，看到“网络和互联网”等设置项。"))

            await(engine) { engine.state().phase == ChatPhase.COMPLETED }
            assertEquals("file:///cache/observe-preview.jpg", engine.state().latestPreviewUri)
            assertEquals(2, engine.state().activeTools.size)
            assertEquals("app.open", engine.state().activeTools[0].tool)
            assertEquals("app.observe", engine.state().activeTools[1].tool)
        } finally {
            engine.close()
            task.close()
        }
    }

    private fun await(engine: ChatEngine, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition()) {
            if (System.nanoTime() >= end) fail("Timed out waiting; state=${engine.state()}")
            Thread.sleep(15)
        }
    }

    private fun textMessage(text: String): Message =
        buildMessage(
            StopReason.END_TURN,
            listOf(ContentBlock.ofText(TextBlock.builder().citations(emptyList()).text(text).build())),
        )

    private fun toolUseMessage(vararg blocks: ContentBlock): Message =
        buildMessage(StopReason.TOOL_USE, blocks.toList())

    private fun buildMessage(reason: StopReason, blocks: List<ContentBlock>): Message =
        JsonValue.from(
            mapOf(
                "id" to "msg_app_${System.nanoTime()}",
                "type" to "message",
                "role" to "assistant",
                "model" to "gemini-2.5-flash",
                "content" to blocks,
                "stop_reason" to reason.asString(),
                "stop_sequence" to null,
                "usage" to mapOf("input_tokens" to 12L, "output_tokens" to 20L),
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

    private class RetryingScriptedTransport : ModelTransport {
        data class Call(
            val history: List<MessageParam>,
            private val completion: (Result<Message>) -> Unit,
        ) {
            fun reply(message: Message) = completion(Result.success(message))
            fun fail(error: Throwable) = completion(Result.failure(error))
        }

        val requests = LinkedBlockingQueue<Call>()
        override fun request(history: List<MessageParam>, completion: (Result<Message>) -> Unit): Closeable =
            requestStreaming(history, {}, completion)
        override fun requestStreaming(
            history: List<MessageParam>,
            onTextDelta: (String) -> Unit,
            completion: (Result<Message>) -> Unit,
        ): Closeable {
            val call = Call(history, completion)
            requests += call
            return Closeable {}
        }
        fun next(): Call = checkNotNull(requests.poll(3, TimeUnit.SECONDS)) { "Expected model request" }
        override fun close() {}
    }

    private class InMemoryRepo : ConversationRepository {
        private val items = LinkedHashMap<String, ChatConversation>()
        private var seq = 1_000L
        override fun list(): List<ChatConversation> = items.values.toList()
        override fun get(id: String): ChatConversation? = items[id]
        override fun create(title: String): ChatConversation =
            ChatConversation(ConversationCodec.newConversationId(), title, ++seq, seq, emptyList()).also { items[it.id] = it }
        override fun appendMessage(conversationId: String, message: ChatMessage, autoTitlePrompt: String?): ChatConversation {
            val cur = checkNotNull(items[conversationId])
            val next = cur.copy(updatedAtMillis = ++seq, messages = cur.messages + message)
            items[conversationId] = next
            return next
        }
        override fun delete(id: String): List<ChatConversation> { items.remove(id); return list() }
        override fun resetUnreadable(): List<ChatConversation> { items.clear(); return emptyList() }
        override fun close() {}
    }

    private class FakeAppCapabilities : TaskCapabilities {
        override fun currentDeviceInfoTarget() = DeviceInfoTarget(10150)
        override fun currentLinuxTarget(): LinuxTarget = error("unused")
        override fun currentDisplayTarget() = DisplayTarget(displayEpoch = 5L, displayId = 2, packageUid = 10150, backendReady = true)
        override fun prepareDeviceInfo(expected: DeviceInfoTarget): OwnedExecution = error("unused")
        override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: Path): OwnedExecution = error("unused")

        override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution = object : OwnedExecution {
            override fun start(completion: (ExecutionOutcome) -> Unit) {
                val res = when (action) {
                    is DisplayAction.OpenApp -> ToolResult(mapOf(
                        "displayId" to expected.displayId.toString(),
                        "packageName" to action.packageName,
                        "launched" to "true",
                        "sourceStatus" to "accepted",
                        "screenshotUri" to "file:///cache/open-preview.jpg",
                    ))
                    DisplayAction.ObserveScreen -> ToolResult(mapOf(
                        "displayId" to expected.displayId.toString(),
                        "uiTreeStatus" to "available",
                        "topPackage" to "com.android.settings",
                        "nodeCount" to "1",
                        "truncated" to "false",
                        "uiElements" to "[1] \"网络和互联网\" center=(360,250) [clickable]",
                        "screenshotUri" to "file:///cache/observe-preview.jpg",
                    ))
                    else -> error("unexpected display action $action")
                }
                completion(ExecutionOutcome.Success(res))
            }
            override fun close() {}
        }
    }
}
