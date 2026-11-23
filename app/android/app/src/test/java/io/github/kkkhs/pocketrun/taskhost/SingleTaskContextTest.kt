package io.github.kkkhs.pocketrun.taskhost

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class SingleTaskContextTest {
    private val workspace = Files.createTempDirectory("pocketrun-context-").toRealPath().also {
        Files.writeString(it.resolve("items.csv"), "item,count\nalpha,2\nbeta,3\n")
    }
    private var now = 0L
    private val clock = MonotonicClock { now }
    private val source = Source()
    private val host = TaskHost(source, clock, NonceSource { "abcdefghijklmnop" })
    private var task = SingleTaskContext(host, clock)
    private val device = RawToolCall("android.device_info", emptyMap())
    private val csv = RawToolCall("linux.count_demo_csv", emptyMap())

    @After fun clean() {
        source.failClose = false
        task.close()
        Files.delete(workspace.resolve("items.csv"))
        Files.delete(workspace)
    }

    @Test fun twoStepsKeepOneGoalAndDistinctApprovalsAndResults() {
        val id = task.begin("read demo").taskId!!
        task.submit(id, device)
        source.complete("apiLevel" to "34")
        assertEquals(ContextPhase.READY, task.state().phase)
        val pending = task.submit(id, csv)
        assertEquals(id, pending.taskId)
        assertEquals(HostPhase.PENDING_APPROVAL, pending.steps.last().status.phase)
        assertEquals(1, source.starts)
        assertTrue(host.onAppUiDecision(pending.steps.last().status.requestNonce!!, AppUiDecision.APPROVE))
        source.complete("lineCount" to "3")
        val done = task.finish(id)
        assertEquals(ContextPhase.COMPLETED, done.phase)
        assertEquals("34", done.steps[0].status.result!!.fields["apiLevel"])
        assertEquals("3", done.steps[1].status.result!!.fields["lineCount"])
        assertNotEquals(done.steps[0].status.requestNonce, done.steps[1].status.requestNonce)
        assertEquals(ApprovalState.APPROVED_BY_APP_UI, done.steps[1].status.approvalState)
    }

    @Test fun oldEntryCannotStealTaskButCurrentApprovalQueryAndCancelRemainUsable() {
        task.withoutActiveTask { host.submit(device) }
        source.complete("apiLevel" to "34")
        val id = task.begin("demo").taskId!!
        val pending = task.submit(id, csv)
        assertThrows(HostBusyException::class.java) { task.withoutActiveTask { host.submit(device) } }
        assertEquals(HostPhase.PENDING_APPROVAL, host.state().phase)
        host.onAppUiDecision(pending.steps[0].status.requestNonce!!, AppUiDecision.REJECT)
        assertEquals(ContextPhase.FAILED, task.state().phase)
        assertEquals(0, source.starts - 1)
        task.withoutActiveTask { host.submit(device) }
    }

    @Test fun rejectAndBindingChangeStopWithoutReplay() {
        var id = task.begin("demo").taskId!!
        var pending = task.submit(id, csv)
        host.onAppUiDecision(pending.steps[0].status.requestNonce!!, AppUiDecision.REJECT)
        assertEquals("USER_REJECTED", task.state().code)
        assertThrows(IllegalStateException::class.java) { task.submit(id, device) }
        id = task.begin("another").taskId!!
        pending = task.submit(id, csv)
        source.generation = "changed"
        host.onAppUiDecision(pending.steps[0].status.requestNonce!!, AppUiDecision.APPROVE)
        assertEquals(ContextPhase.FAILED, task.state().phase)
        assertEquals("TARGET_CHANGED", task.state().code)
        assertEquals(0, source.starts)
    }

    @Test fun cancelledLateCallbackCannotContaminateNextTask() {
        val firstId = task.begin("first").taskId!!
        task.submit(firstId, device)
        val oldCallback = source.callback!!
        task.cancel(firstId)
        assertEquals(ContextPhase.CANCELLED, task.state().phase)
        val nextId = task.begin("next").taskId!!
        task.submit(nextId, device)
        oldCallback(ExecutionOutcome.Success(ToolResult(mapOf("old" to "late"))))
        assertEquals(ContextPhase.RUNNING, task.state().phase)
        assertNull(task.state().steps.single().status.result)
        assertThrows(IllegalStateException::class.java) { task.submit(firstId, device) }
        source.complete("new" to "current")
        assertEquals("current", task.state().steps.single().status.result!!.fields["new"])
    }

    @Test fun countAndInputLengthBudgetsStopBeforeNextExecution() {
        task = SingleTaskContext(host, clock, ContextBudget(maxSteps = 1))
        assertThrows(IllegalArgumentException::class.java) { task.begin("中".repeat(400)) }
        val id = task.begin("one").taskId!!
        task.submit(id, device)
        source.complete("value" to "demo")
        assertEquals("STEP_BUDGET", task.submit(id, device).code)
        assertEquals(1, source.starts)
    }

    @Test fun resultAndContextBudgetsNeverTruncateIntoSuccess() {
        task = SingleTaskContext(host, clock, ContextBudget(maxResultBytes = 256))
        var id = task.begin("one").taskId!!
        task.submit(id, device)
        source.complete("value" to "x".repeat(257))
        assertEquals(ContextPhase.BUDGET_EXHAUSTED, task.state().phase)
        assertNull(task.state().steps.single().status.result)
        task = SingleTaskContext(host, clock, ContextBudget(maxContextBytes = 1024, maxResultBytes = 512))
        id = task.begin("one").taskId!!
        task.submit(id, device)
        source.complete("value" to "x".repeat(400))
        assertEquals("CONTEXT_BUDGET", task.state().code)
        assertTrue(task.state().contextBytes <= 1024)
    }

    @Test fun timeBudgetCancelsPendingApprovalAndPreventsLateConsent() {
        task = SingleTaskContext(host, clock, ContextBudget(maxDurationMillis = 10))
        val id = task.begin("one").taskId!!
        val pending = task.submit(id, csv)
        now += TimeUnit.MILLISECONDS.toNanos(10)
        assertEquals("TIME_BUDGET", task.state().code)
        assertFalse(host.onAppUiDecision(pending.steps.single().status.requestNonce!!, AppUiDecision.APPROVE))
        assertEquals(0, source.starts)
    }

    @Test fun unprovenCleanupRetainsSlotAndRecoveryDoesNotClaimSuccess() {
        val id = task.begin("one").taskId!!
        task.submit(id, device)
        source.failClose = true
        source.complete("value" to "demo")
        assertEquals(ContextPhase.FAILED, task.state().phase)
        assertEquals(CleanupState.UNPROVEN, task.state().steps.single().status.cleanupState)
        assertThrows(HostBusyException::class.java) { task.begin("next") }
        source.failClose = false
        assertTrue(host.retryCleanup())
        assertEquals(ContextPhase.FAILED, task.state().phase)
        assertEquals(CleanupState.PROVEN, task.state().steps.single().status.cleanupState)
    }

    @Test fun unknownOrPrivilegeEscalatingToolFailsTaskWithoutCapabilityStart() {
        val id = task.begin("one").taskId!!
        val invalid = task.submit(id, RawToolCall("linux.count_demo_csv", mapOf("approved" to true)))
        assertEquals(ContextPhase.FAILED, invalid.phase)
        assertEquals(0, source.starts)
    }

    private inner class Source : TaskCapabilities {
        var callback: ((ExecutionOutcome) -> Unit)? = null
        var starts = 0
        var failClose = false
        var generation = "demo-1"
        override fun currentDeviceInfoTarget() = DeviceInfoTarget(10001)
        override fun currentLinuxTarget() = LinuxTarget(generation, 10001, workspace, true, true)
        override fun currentDisplayTarget() = DisplayTarget(1, 1, 10001, false)
        override fun prepareDeviceInfo(expected: DeviceInfoTarget) = execution()
        override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: java.nio.file.Path) = execution()
        override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction) = execution()
        private fun execution() = object : OwnedExecution {
            override fun start(completion: (ExecutionOutcome) -> Unit) { starts++; callback = completion }
            override fun close() { check(!failClose) { "close failed" } }
        }
        fun complete(field: Pair<String, String>) {
            callback!!(ExecutionOutcome.Success(ToolResult(mapOf(field))))
        }
    }
}
