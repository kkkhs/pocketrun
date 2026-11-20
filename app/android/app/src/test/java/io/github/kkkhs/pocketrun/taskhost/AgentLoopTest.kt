package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.DirectCaller
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.ThinkingBlock
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.Usage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Scripted model messages exercise the real controller/host; these are NOT real-model evidence. */
class AgentLoopTest {
    private val workspace = Files.createTempDirectory("pocketrun-agent-").toRealPath().also {
        Files.writeString(it.resolve("items.csv"), "demo\n")
    }
    @Volatile private var now = 0L
    private val source = Capabilities()
    private val clock = MonotonicClock { now }
    private val host = TaskHost(source, clock, NonceSource { "abcdefghijklmnop" })
    private val task = SingleTaskContext(host, clock)
    private val agent = AgentLoop(task, clock)
    private val model = ScriptedTransport()

    @After fun clean() {
        source.failClose = false
        agent.close(); task.close()
        Files.delete(workspace.resolve("items.csv")); Files.delete(workspace)
    }

    @Test fun toolResultWaitsForPhoneAndReturnsActualIdWithFullAssistantContent() {
        agent.start("read demo", model, "linuxReady=true")
        val first = model.next()
        first.reply(message(StopReason.TOOL_USE, listOf(
            ContentBlock.ofThinking(ThinkingBlock.builder().thinking("summary").signature("signed-demo").build()),
            call("csv-1", "linux_count_demo_csv"))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        assertEquals(0, source.starts)
        assertTrue(model.requests.isEmpty())
        assertTrue(host.onAppUiDecision(host.state().requestNonce!!, AppUiDecision.APPROVE))
        source.complete("lineCount" to "3", "stdout" to "PRIVATE_DATA_NEVER_SEND", "fileUri" to "file://private")
        val second = model.next()
        val serialized = second.history.joinToString()
        assertTrue(serialized.contains("signed-demo"))
        assertTrue(serialized.contains("csv-1"))
        assertTrue(serialized.contains("lineCount=3"))
        assertFalse(serialized.contains("PRIVATE_DATA_NEVER_SEND"))
        assertFalse(serialized.contains("file://private"))
        second.reply(final())
        await { agent.state().phase == AgentPhase.COMPLETED }
        assertEquals(ContextPhase.COMPLETED, task.state().phase)
        assertEquals(2, agent.state().rounds)
    }

    @Test fun rejectionStopsBeforeToolExecutionOrNextModelRound() {
        agent.start("read demo", model, "demo")
        model.next().reply(message(StopReason.TOOL_USE, listOf(call("csv-1", "linux_count_demo_csv"))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        host.onAppUiDecision(host.state().requestNonce!!, AppUiDecision.REJECT)
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals(0, source.starts)
        assertTrue(model.requests.isEmpty())
    }

    @Test fun cancellingModelRequestFencesLateReplyAndNewTask() {
        agent.start("one", model, "demo")
        val first = model.next()
        agent.cancel()
        assertTrue(first.cancelled)
        val nextModel = ScriptedTransport()
        agent.start("two", nextModel, "demo")
        first.reply(message(StopReason.TOOL_USE, listOf(call("late", "android_device_info"))))
        nextModel.next()
        assertEquals(AgentPhase.MODEL, agent.state().phase)
        assertEquals(0, source.starts)
        assertTrue(task.state().steps.isEmpty())
    }

    @Test fun configChangeWhileWaitingForApprovalInvalidatesNonceAndKeepsOriginalCleanupOwner() {
        // TaskHostModule invokes this same controller cancellation before saving configuration.
        agent.start("old config", model, "demo")
        val oldRound = model.next()
        oldRound.reply(message(StopReason.TOOL_USE, listOf(call("old-csv", "linux_count_demo_csv"))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        val oldNonce = host.state().requestNonce!!
        agent.cancel("MODEL_CONFIG_CHANGED")
        assertEquals(AgentPhase.CANCELLED, agent.state().phase)
        assertEquals("MODEL_CONFIG_CHANGED", agent.state().code)
        assertEquals(ApprovalState.CANCELLED_BY_APP_UI, host.state().approvalState)
        assertFalse(host.onAppUiDecision(oldNonce, AppUiDecision.APPROVE))
        assertEquals(0, source.starts)
        assertEquals(0, source.prepares) // No owner exists before phone approval.
        assertEquals(CleanupState.NONE, host.state().cleanupState)
        assertTrue(source.closedOwners.isEmpty())
        assertTrue(model.requests.isEmpty())

        val newModel = ScriptedTransport()
        agent.start("new config", newModel, "demo")
        val newRound = newModel.next()
        oldRound.reply(message(StopReason.TOOL_USE, listOf(call("late-device", "android_device_info"))))
        newRound.reply(message(StopReason.TOOL_USE, listOf(call("new-csv", "linux_count_demo_csv"))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        val newNonce = host.state().requestNonce!!
        assertNotEquals(oldNonce, newNonce)
        assertFalse(host.onAppUiDecision(oldNonce, AppUiDecision.APPROVE))
        assertEquals(0, source.starts)
        assertEquals(1, task.state().steps.size)

        // Once approval creates an owner, config cancellation must retain it on failed cleanup.
        assertTrue(host.onAppUiDecision(newNonce, AppUiDecision.APPROVE))
        assertEquals(1, source.starts)
        source.failClose = true
        agent.cancel("MODEL_CONFIG_CHANGED")
        assertEquals(CleanupState.UNPROVEN, host.state().cleanupState)
        assertEquals(listOf(1), source.closedOwners.toList())
        assertEquals(newNonce, host.state().requestNonce)
        assertFalse(host.onAppUiDecision(newNonce, AppUiDecision.APPROVE))
        try { agent.start("blocked config", ScriptedTransport(), "demo"); fail("cleanup must retain the slot") }
        catch (_: HostBusyException) { /* original owner still holds the slot */ }
        source.complete("lineCount" to "3")
        assertEquals(AgentPhase.CANCELLED, agent.state().phase)
        assertTrue(newModel.requests.isEmpty())
        source.failClose = false
        assertTrue(host.retryCleanup())
        assertEquals(listOf(1, 1), source.closedOwners.toList())
        assertEquals(1, source.prepares)
        assertEquals(CleanupState.PROVEN, host.state().cleanupState)

        val finalModel = ScriptedTransport()
        agent.start("after cleanup", finalModel, "demo")
        val finalRound = finalModel.next()
        newRound.reply(message(StopReason.TOOL_USE, listOf(call("late-new", "android_device_info"))))
        source.complete("lineCount" to "99")
        finalRound.reply(message(StopReason.TOOL_USE, listOf(call("final-csv", "linux_count_demo_csv"))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        assertEquals(AgentPhase.TOOL, agent.state().phase)
        assertEquals("linux.count_demo_csv", task.state().steps.single().call.tool)
        assertFalse(host.onAppUiDecision(newNonce, AppUiDecision.APPROVE))
        assertEquals(1, source.starts)
        assertEquals(1, source.prepares)
    }

    @Test fun multipleToolBlocksExecuteSeriallyAndReturnTogether() {
        agent.start("demo", model, "demo")
        model.next().reply(message(StopReason.TOOL_USE, listOf(
            call("device-1", "android_device_info"), call("device-2", "android_device_info"))))
        await { source.starts == 1 }
        source.complete("apiLevel" to "34")
        await { source.starts == 2 }
        source.complete("apiLevel" to "34")
        val next = model.next()
        assertTrue(next.history.last().toString().contains("device-1"))
        assertTrue(next.history.last().toString().contains("device-2"))
        assertEquals(3, next.history.size)
        next.reply(final())
        await { agent.state().phase == AgentPhase.COMPLETED }
    }

    @Test fun invalidWholeTurnDoesNotExecuteItsFirstValidCall() {
        agent.start("demo", model, "demo")
        model.next().reply(message(StopReason.TOOL_USE, listOf(
            call("device-1", "android_device_info"), call("approve-1", "approve"))))
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals(0, source.starts)
    }

    @Test fun duplicateIdsAndIncompleteMessagesFailWithoutStartingTools() {
        agent.start("demo", model, "demo")
        model.next().reply(message(StopReason.TOOL_USE, listOf(
            call("duplicate", "android_device_info"), call("duplicate", "android_device_info"))))
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals(0, source.starts)
        val another = ScriptedTransport()
        agent.start("demo", another, "demo")
        another.next().reply(message(StopReason.MAX_TOKENS, listOf(call("partial", "android_device_info"))))
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals("INCOMPLETE_MODEL_RESPONSE", agent.state().code)
    }

    @Test fun requestTimeoutAndOversizedContextStopAndCancel() {
        agent.start("demo", model, "demo")
        val pending = model.next()
        now += TimeUnit.SECONDS.toNanos(25)
        await { agent.state().phase == AgentPhase.BUDGET_EXHAUSTED }
        assertTrue(pending.cancelled)
        val another = ScriptedTransport()
        agent.start("demo", another, "demo")
        another.next().reply(message(StopReason.TOOL_USE, listOf(
            ContentBlock.ofText(TextBlock.builder().citations(emptyList()).text("x".repeat(65_536)).build()),
            call("device", "android_device_info"))))
        await { agent.state().phase == AgentPhase.BUDGET_EXHAUSTED }
        assertEquals(0, source.starts)
    }

    @Test fun oldModelRoundCannotSettleOrReplaceTheNextRequest() {
        agent.start("demo", model, "demo")
        val first = model.next()
        first.reply(message(StopReason.TOOL_USE, listOf(call("device", "android_device_info"))))
        await { source.starts == 1 }
        source.complete("apiLevel" to "34")
        val next = model.next()
        first.reply(message(StopReason.TOOL_USE, listOf(call("obsolete", "approve"))))
        next.reply(final())
        await { agent.state().phase == AgentPhase.COMPLETED }
        assertEquals(1, source.starts)
    }

    @Test fun eightRoundsEndWithoutNinthRequestOrReplayedTools() {
        agent.start("demo", model, "demo")
        repeat(8) { index ->
            model.next().reply(message(StopReason.TOOL_USE, listOf(call("device-$index", "android_device_info"))))
            await { source.starts == index + 1 }
            source.complete("apiLevel" to "34")
        }
        await { agent.state().phase == AgentPhase.BUDGET_EXHAUSTED }
        assertEquals(8, agent.state().rounds)
        assertTrue(model.requests.isEmpty())
    }

    @Test fun cleanupFailureNeverFeedsUnprovenResultBackToModel() {
        agent.start("demo", model, "demo")
        model.next().reply(message(StopReason.TOOL_USE, listOf(call("device", "android_device_info"))))
        await { source.starts == 1 }
        source.failClose = true
        source.complete("apiLevel" to "34")
        await { agent.state().phase == AgentPhase.FAILED }
        assertTrue(model.requests.isEmpty())
        assertEquals(CleanupState.UNPROVEN, host.state().cleanupState)
        source.failClose = false
        host.retryCleanup()
        assertEquals(AgentPhase.FAILED, agent.state().phase)
    }

    @Test fun pendingDisplayDeathStopsTurnAndOldEpochCannotAffectNextAgent() {
        val oldTarget = DisplayTarget(1L, 1, 10001, true)
        source.displayTarget = oldTarget
        agent.start("old display", model, "displayReady=true")
        val oldRound = model.next()
        oldRound.reply(message(StopReason.TOOL_USE, listOf(
            displayCall("old-input", oldTarget), call("must-not-run", "android_device_info"))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        val oldNonce = host.state().requestNonce!!
        source.displayTarget = oldTarget.copy(backendReady = false)
        val binding = TaskHostBackendDeath.binding(host.state(), oldTarget)!!
        assertTrue(host.onBackendDeath(binding.requestNonce, binding.backendEpoch))
        assertFalse(host.onAppUiDecision(oldNonce, AppUiDecision.APPROVE))
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals("BACKEND_DEATH", agent.state().code)
        assertEquals(0, source.prepares)
        assertEquals(0, source.starts)
        assertTrue(model.closed)
        assertTrue(model.requests.isEmpty())

        val newTarget = oldTarget.copy(displayEpoch = 2L)
        source.displayTarget = newTarget
        val nextModel = ScriptedTransport()
        agent.start("new display", nextModel, "displayReady=true")
        val nextRound = nextModel.next()
        oldRound.reply(message(StopReason.TOOL_USE, listOf(call("late", "android_device_info"))))
        nextRound.reply(message(StopReason.TOOL_USE, listOf(displayCall("new-input", newTarget))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        assertNull(TaskHostBackendDeath.binding(host.state(), oldTarget))
        assertFalse(host.onAppUiDecision(oldNonce, AppUiDecision.APPROVE))
        assertTrue(host.onAppUiDecision(host.state().requestNonce!!, AppUiDecision.APPROVE))
        source.complete("displayId" to "1", "displayEpoch" to "2", "action" to "demo-text",
            "screenshotUri" to "file:///private-frame", "uiTreeText" to "PRIVATE_UI_NEVER_SEND")
        val resultRound = nextModel.next()
        val results = resultRound.history.last().toString()
        assertTrue(results.contains("new-input"))
        assertTrue(results.contains("displayEpoch=2"))
        assertFalse(results.contains("file:///private-frame"))
        assertFalse(results.contains("PRIVATE_UI_NEVER_SEND"))
        resultRound.reply(final())
        await { agent.state().phase == AgentPhase.COMPLETED }
        assertEquals(1, source.starts)
        assertEquals(CleanupState.PROVEN, task.state().steps.single().status.cleanupState)
    }

    @Test fun displayEpochChangeBeforeApprovalStopsAgentWithoutOwnerOrNextRound() {
        val target = DisplayTarget(1L, 1, 10001, true)
        source.displayTarget = target
        agent.start("changed display", model, "displayReady=true")
        model.next().reply(message(StopReason.TOOL_USE, listOf(displayCall("input", target))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        source.displayTarget = target.copy(displayEpoch = 2L)
        host.onAppUiDecision(host.state().requestNonce!!, AppUiDecision.APPROVE)
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals("TARGET_CHANGED", agent.state().code)
        assertEquals(CleanupState.NONE, host.state().cleanupState)
        assertEquals(0, source.prepares)
        assertEquals(0, source.starts)
        assertTrue(model.requests.isEmpty())
    }

    @Test fun runningDisplayDeathRetainsOriginalFailedCleanupAndFencesLateResult() {
        val target = DisplayTarget(1L, 1, 10001, true)
        source.displayTarget = target
        agent.start("running display", model, "displayReady=true")
        model.next().reply(message(StopReason.TOOL_USE, listOf(displayCall("input", target))))
        await { host.state().phase == HostPhase.PENDING_APPROVAL }
        val nonce = host.state().requestNonce!!
        assertTrue(host.onAppUiDecision(nonce, AppUiDecision.APPROVE))
        val late = source.callback!!
        source.failClose = true
        source.displayTarget = target.copy(backendReady = false)
        val binding = TaskHostBackendDeath.binding(host.state(), target)!!
        assertTrue(host.onBackendDeath(binding.requestNonce, binding.backendEpoch))
        await { agent.state().phase == AgentPhase.FAILED }
        assertEquals(CleanupState.UNPROVEN, host.state().cleanupState)
        assertEquals(nonce, host.state().requestNonce)
        assertEquals(listOf(1), source.closedOwners.toList())
        assertThrows(HostBusyException::class.java) { agent.start("blocked", ScriptedTransport(), "demo") }
        late(ExecutionOutcome.Success(ToolResult(mapOf("displayEpoch" to "1"))))
        assertTrue(model.requests.isEmpty())
        source.failClose = false
        assertTrue(host.retryCleanup())
        assertEquals(listOf(1, 1), source.closedOwners.toList())
        assertEquals(1, source.prepares)
        assertEquals(AgentPhase.FAILED, agent.state().phase)
        assertFalse(host.onAppUiDecision(nonce, AppUiDecision.APPROVE))
    }

    private fun displayCall(id: String, target: DisplayTarget) = call(id, "android_virtual_display", mapOf(
        "action" to "demo_text", "text" to "synthetic", "expectedDisplayId" to target.displayId,
        "expectedDisplayEpoch" to target.displayEpoch))
    private fun call(id: String, name: String, input: Map<String, Any> = emptyMap()) = ContentBlock.ofToolUse(ToolUseBlock.builder()
        .id(id).name(name).caller(DirectCaller.builder().build()).input(JsonValue.from(input)).build())
    private fun final() = message(StopReason.END_TURN, listOf(ContentBlock.ofText(TextBlock.builder().citations(emptyList()).text("Demo done.").build())))
    private fun message(reason: StopReason, blocks: List<ContentBlock>): Message = JsonValue.from(mapOf(
        "id" to "msg-demo", "type" to "message", "role" to "assistant", "model" to "claude-opus-5-5",
        "content" to blocks, "stop_reason" to reason.asString(), "stop_sequence" to null,
        "usage" to mapOf("input_tokens" to 1, "output_tokens" to 4),
    )).convert(Message::class.java)!!

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("condition did not settle", condition())
    }

    private class ScriptedTransport : ModelTransport {
        val requests = LinkedBlockingQueue<Pending>()
        @Volatile var closed = false
        override fun request(history: List<MessageParam>, completion: (Result<Message>) -> Unit): Closeable =
            Pending(history, completion).also { requests.put(it) }
        fun next() = checkNotNull(requests.poll(3, TimeUnit.SECONDS)) { "Missing model round" }
        override fun close() { closed = true }
        class Pending(val history: List<MessageParam>, private val completion: (Result<Message>) -> Unit) : Closeable {
            @Volatile var cancelled = false
            fun reply(message: Message) = completion(Result.success(message))
            override fun close() { cancelled = true }
        }
    }

    private inner class Capabilities : TaskCapabilities {
        @Volatile var starts = 0
        @Volatile var prepares = 0
        val closedOwners = java.util.concurrent.CopyOnWriteArrayList<Int>()
        @Volatile var failClose = false
        @Volatile var callback: ((ExecutionOutcome) -> Unit)? = null
        override fun currentDeviceInfoTarget() = DeviceInfoTarget(10001)
        override fun currentLinuxTarget() = LinuxTarget("demo-1", 10001, workspace, true, true)
        @Volatile var displayTarget = DisplayTarget(1, 1, 10001, false)
        override fun currentDisplayTarget() = displayTarget
        override fun prepareDeviceInfo(expected: DeviceInfoTarget) = execution()
        override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: java.nio.file.Path) = execution()
        override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction) = execution()
        private fun execution(): OwnedExecution {
            val owner = ++prepares
            return object : OwnedExecution {
                override fun start(completion: (ExecutionOutcome) -> Unit) { callback = completion; starts++ }
                override fun close() { closedOwners += owner; check(!failClose) }
            }
        }
        fun complete(vararg fields: Pair<String, String>) = callback!!(ExecutionOutcome.Success(ToolResult(mapOf(*fields))))
    }
}
