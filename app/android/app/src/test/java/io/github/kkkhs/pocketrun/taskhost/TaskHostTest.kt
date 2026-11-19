package io.github.kkkhs.pocketrun.taskhost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class TaskHostTest {
    private lateinit var workspace: Path
    private lateinit var clock: FakeClock
    private lateinit var capabilities: FakeCapabilities
    private lateinit var host: TaskHost

    @Before
    fun setUp() {
        workspace = Files.createTempDirectory("pocketrun-ch9-host-").toRealPath()
        Files.writeString(workspace.resolve("items.csv"), "item,count\nalpha,2\nbeta,3\n")
        clock = FakeClock()
        capabilities = FakeCapabilities(workspace)
        host = TaskHost(capabilities, clock, NonceSource { "abcdefghijklmnop" },
            approvalTtlMillis = 100, executionTimeoutMillis = 200)
    }

    @After
    fun tearDown() {
        host.close()
        Files.walk(workspace).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun sensitiveHandlerRunsOnlyAfterCurrentAppUiApproval() {
        val pending = host.submit(displayText("demo"))
        assertEquals(HostPhase.PENDING_APPROVAL, pending.phase)
        assertEquals(TaskHost.APP_PHONE_UI_ACTOR, pending.approvalActor)
        assertEquals(ApprovalState.PENDING_APP_UI, pending.approvalState)
        assertEquals(0, capabilities.prepareDisplayCalls)
        assertFalse(host.onAppUiDecision("wrong_nonce", AppUiDecision.APPROVE))
        assertEquals(0, capabilities.prepareDisplayCalls)

        assertTrue(host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE))
        assertEquals(HostPhase.EXECUTING, host.state().phase)
        assertEquals(ApprovalState.APPROVED_BY_APP_UI, host.state().approvalState)
        assertEquals(1, capabilities.prepareDisplayCalls)
        assertEquals(DisplayAction.DemoText("demo"), capabilities.lastDisplayAction)
    }

    @Test
    fun rejectConsumesApprovalAndOldApprovalCannotAffectNextRun() {
        val first = host.submit(displayTap(10, 20))
        val oldNonce = notNull(first.requestNonce)
        assertTrue(host.onAppUiDecision(oldNonce, AppUiDecision.REJECT))
        assertEquals(HostPhase.REJECTED, host.state().phase)
        assertFalse(host.onAppUiDecision(oldNonce, AppUiDecision.APPROVE))
        assertEquals(0, capabilities.prepareDisplayCalls)

        val second = host.submit(displayTap(11, 21))
        assertNotEquals(oldNonce, second.requestNonce)
        assertFalse(host.onAppUiDecision(oldNonce, AppUiDecision.APPROVE))
        assertEquals(HostPhase.PENDING_APPROVAL, host.state().phase)
        assertEquals(0, capabilities.prepareDisplayCalls)
    }

    @Test
    fun approvalExpiresUsingOnlyMonotonicTime() {
        val pending = host.submit(displayTap(1, 2))
        clock.advanceMillis(100)
        assertEquals(HostPhase.EXPIRED, host.state().phase)
        assertEquals("APPROVAL_EXPIRED", host.state().code)
        assertFalse(host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE))
        assertEquals(0, capabilities.prepareDisplayCalls)
    }

    @Test
    fun monotonicDeadlineSurvivesSignedNanoTimeWrap() {
        clock.now = Long.MAX_VALUE - TimeUnit.MILLISECONDS.toNanos(50)
        host.submit(displayTap(1, 2))
        clock.advanceMillis(99)
        assertEquals(HostPhase.PENDING_APPROVAL, host.state().phase)
        clock.advanceMillis(1)
        assertEquals(HostPhase.EXPIRED, host.state().phase)
    }

    @Test
    fun approvalExpiringDuringLockedTargetRecheckNeverCallsAdapter() {
        val pending = host.submit(displayTap(1, 2))
        capabilities.onDisplayRead = { clock.advanceMillis(100) }
        assertTrue(host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE))
        assertEquals(HostPhase.EXPIRED, host.state().phase)
        assertEquals("APPROVAL_EXPIRED", host.state().code)
        assertEquals(0, capabilities.prepareDisplayCalls)
        assertFalse(host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE))
    }

    @Test
    fun displayEpochToctouInvalidatesApprovedRequestBeforeHandler() {
        val pending = host.submit(displayTap(1, 2))
        capabilities.display = capabilities.display.copy(displayEpoch = capabilities.display.displayEpoch + 1)
        assertTrue(host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE))
        assertEquals(HostPhase.INVALIDATED, host.state().phase)
        assertEquals("TARGET_CHANGED", host.state().code)
        assertEquals(0, capabilities.prepareDisplayCalls)
    }

    @Test
    fun backendNotReadyIsExplicitlyRejectedWithoutDefaultDisplayFallback() {
        capabilities.display = capabilities.display.copy(displayId = -1, backendReady = false)
        validation("BACKEND_NOT_READY") { host.submit(displayObserve(9, 4L)) }
        assertEquals(HostPhase.INVALID_INPUT, host.state().phase)
        assertEquals(0, capabilities.prepareDisplayCalls)
    }

    @Test
    fun expectedDisplayIdAndEpochMustMatchAtValidation() {
        validation("DISPLAY_TARGET_MISMATCH") { host.submit(displayObserve(8, 4L)) }
        assertEquals(0, capabilities.prepareDisplayCalls)
    }

    @Test
    fun linuxToolUsesOnlyVerifiedGenerationAndFixedCanonicalCsv() {
        val pending = host.submit(RawToolCall("linux.count_demo_csv", emptyMap()))
        assertEquals(HostPhase.PENDING_APPROVAL, pending.phase)
        assertEquals(0, capabilities.prepareLinuxCalls)
        host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE)
        val executing = host.state()
        assertEquals(HostPhase.EXECUTING, executing.phase)
        assertEquals(1, capabilities.prepareLinuxCalls)
        assertEquals(workspace.resolve("items.csv"), capabilities.lastCsv)
        assertEquals("/workspace/items.csv · rootfs rootfs-7 · UID 10469", executing.target)
    }

    @Test
    fun unverifiedLinuxSessionNeverRuns() {
        capabilities.linux = capabilities.linux.copy(verifiedLinuxSession = false)
        validation("LINUX_SESSION_NOT_VERIFIED") {
            host.submit(RawToolCall("linux.count_demo_csv", emptyMap()))
        }
        assertEquals(0, capabilities.prepareLinuxCalls)
    }

    @Test
    fun linuxGenerationToctouStopsBeforePreparingHandler() {
        capabilities.afterFirstLinuxRead = capabilities.linux.copy(rootfsGeneration = "rootfs-8")
        val pending = host.submit(RawToolCall("linux.count_demo_csv", emptyMap()))
        host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE)
        val result = host.state()
        assertEquals(HostPhase.INVALIDATED, result.phase)
        assertEquals("TARGET_CHANGED", result.code)
        assertEquals(0, capabilities.prepareLinuxCalls)
    }

    @Test
    fun deviceInfoRunsAsOrdinaryBoundPackageUidWithoutApproval() {
        val state = host.submit(RawToolCall("android.device_info", emptyMap()))
        assertEquals(HostPhase.EXECUTING, state.phase)
        assertEquals("PocketRun App UID 10469", state.target)
        assertEquals(1, capabilities.prepareDeviceCalls)
    }

    @Test
    fun requestHashAndViewBindUidDisplayIdEpochAndAction() {
        val first = host.submit(displayTap(3, 4))
        assertEquals(64, notNull(first.requestHash).length)
        assertTrue(notNull(first.target).contains("virtual display 9"))
        assertTrue(notNull(first.target).contains("epoch 4"))
        host.onAppUiDecision(notNull(first.requestNonce), AppUiDecision.REJECT)

        capabilities.display = capabilities.display.copy(displayEpoch = 5)
        val second = host.submit(displayTap(3, 4, epoch = 5L))
        assertNotEquals(first.requestHash, second.requestHash)
    }

    @Test
    fun cancellingPendingApprovalConsumesNonceWithoutCallingHandler() {
        val pending = host.submit(displayTap(3, 4))
        assertTrue(host.cancelFromAppUi(notNull(pending.requestNonce)))
        assertEquals(HostPhase.CANCELLED, host.state().phase)
        assertEquals(ApprovalState.CANCELLED_BY_APP_UI, host.state().approvalState)
        assertEquals(0, capabilities.prepareDisplayCalls)
        assertFalse(host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE))
    }

    @Test
    fun cancellingExecutionClosesSoleOwnedResource() {
        val pending = host.submit(displayTap(3, 4))
        host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE)
        val execution = capabilities.executions.single()
        assertEquals(1, execution.starts)
        assertEquals(0, execution.closes)

        assertTrue(host.cancelFromAppUi(notNull(pending.requestNonce)))
        assertEquals(HostPhase.CANCELLED, host.state().phase)
        assertEquals(1, execution.closes)
        assertFalse(host.cancelFromAppUi(notNull(pending.requestNonce)))
    }

    @Test
    fun executionTimeoutClosesResource() {
        host.submit(RawToolCall("android.device_info", emptyMap()))
        val execution = capabilities.executions.single()
        clock.advanceMillis(200)
        assertEquals(HostPhase.EXPIRED, host.state().phase)
        assertEquals("EXECUTION_TIMEOUT", host.state().code)
        assertEquals(1, execution.closes)
    }

    @Test
    fun backendDeathClosesResourceAndGetsDedicatedState() {
        val running = host.submit(RawToolCall("android.device_info", emptyMap()))
        val execution = capabilities.executions.single()
        assertTrue(host.onBackendDeath(notNull(running.requestNonce), notNull(running.backendEpoch)))
        assertEquals(HostPhase.INVALIDATED, host.state().phase)
        assertEquals("BACKEND_DEATH", host.state().code)
        assertEquals(1, execution.closes)
    }

    @Test
    fun lateCallbackCannotCompleteOrCancelNextRun() {
        val first = host.submit(displayTap(3, 4))
        host.onAppUiDecision(notNull(first.requestNonce), AppUiDecision.APPROVE)
        val oldExecution = capabilities.executions.single()
        host.cancelFromAppUi(notNull(first.requestNonce))

        val second = host.submit(displayObserve(9, 4L))
        val newExecution = capabilities.executions.last()
        assertEquals(HostPhase.EXECUTING, second.phase)
        oldExecution.complete(ExecutionOutcome.Success(ToolResult(mapOf("old" to "late"))))
        assertEquals(HostPhase.EXECUTING, host.state().phase)
        assertEquals(0, newExecution.closes)

        newExecution.complete(ExecutionOutcome.Success(ToolResult(mapOf("displayId" to "9"))))
        assertEquals(HostPhase.SUCCEEDED, host.state().phase)
        assertEquals("9", host.state().result?.fields?.get("displayId"))
        assertEquals(1, newExecution.closes)
    }

    @Test
    fun lateSuccessAfterDeadlineIsExpiredNotSucceeded() {
        host.submit(RawToolCall("android.device_info", emptyMap()))
        val execution = capabilities.executions.single()
        clock.advanceMillis(200)
        execution.complete(ExecutionOutcome.Success(ToolResult(mapOf("late" to "success"))))
        assertEquals(HostPhase.EXPIRED, host.state().phase)
        assertEquals("EXECUTION_TIMEOUT", host.state().code)
        assertEquals(1, execution.closes)
    }

    @Test
    fun displayChangeDuringExecutionInvalidatesSuccess() {
        host.submit(displayObserve(9, 4L))
        val execution = capabilities.executions.single()
        capabilities.display = capabilities.display.copy(displayEpoch = 5L)
        execution.complete(ExecutionOutcome.Success(ToolResult(mapOf("displayId" to "9"))))
        assertEquals(HostPhase.INVALIDATED, host.state().phase)
        assertEquals("TARGET_CHANGED", host.state().code)
        assertEquals(1, execution.closes)
    }

    @Test
    fun rootfsGenerationChangeDuringExecutionInvalidatesSuccess() {
        val pending = host.submit(RawToolCall("linux.count_demo_csv", emptyMap()))
        host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE)
        val execution = capabilities.executions.single()
        capabilities.linux = capabilities.linux.copy(rootfsGeneration = "rootfs-8")
        execution.complete(ExecutionOutcome.Success(ToolResult(mapOf("count" to "3"))))
        assertEquals(HostPhase.INVALIDATED, host.state().phase)
        assertEquals("TARGET_CHANGED", host.state().code)
        assertEquals(1, execution.closes)
    }

    @Test
    fun preparationReturningAfterDeadlineNeverStartsSensitiveCapability() {
        capabilities.prepareHook = { clock.advanceMillis(201) }
        val pending = host.submit(displayTap(2, 3))
        host.onAppUiDecision(notNull(pending.requestNonce), AppUiDecision.APPROVE)
        val execution = capabilities.executions.single()
        assertEquals(0, execution.starts)
        assertEquals(1, execution.closes)
        assertEquals(HostPhase.EXPIRED, host.state().phase)
    }

    @Test
    fun staleBackendDeathCannotKillNextRequest() {
        val first = host.submit(RawToolCall("android.device_info", emptyMap()))
        host.cancelFromAppUi(notNull(first.requestNonce))
        val second = host.submit(RawToolCall("android.device_info", emptyMap()))
        assertFalse(host.onBackendDeath(notNull(first.requestNonce), notNull(first.backendEpoch)))
        assertEquals(HostPhase.EXECUTING, host.state().phase)
        assertTrue(host.onBackendDeath(notNull(second.requestNonce), notNull(second.backendEpoch)))
        assertEquals("BACKEND_DEATH", host.state().code)
    }

    @Test
    fun cancellationBetweenOwnerInstallAndStartCannotStartClosedLease() {
        host.close()
        lateinit var raceHost: TaskHost
        lateinit var nonce: String
        raceHost = TaskHost.withStartProbe(capabilities, clock,
            NonceSource { "raceabcdefghijkl" }, approvalTtlMillis = 100,
            executionTimeoutMillis = 200,
            beforeOwnedStart = { raceHost.cancelFromAppUi(nonce) })
        host = raceHost
        val pending = host.submit(displayTap(2, 3))
        nonce = notNull(pending.requestNonce)
        host.onAppUiDecision(nonce, AppUiDecision.APPROVE)
        val execution = capabilities.executions.single()
        assertEquals(0, execution.starts)
        assertEquals(1, execution.closes)
        assertEquals(HostPhase.CANCELLED, host.state().phase)
    }

    @Test
    fun closeFailureRetainsSlotUntilRetryAndNeverClaimsSuccess() {
        capabilities.closeFailures = 1
        host.submit(RawToolCall("android.device_info", emptyMap()))
        val execution = capabilities.executions.single()
        execution.complete(ExecutionOutcome.Success(ToolResult(mapOf("apiLevel" to "36"))))
        assertEquals(HostPhase.FAILED, host.state().phase)
        assertEquals("RESOURCE_CLEANUP_UNPROVEN", host.state().code)
        assertEquals(CleanupState.UNPROVEN, host.state().cleanupState)
        try {
            host.submit(RawToolCall("android.device_info", emptyMap()))
            fail("Unproven cleanup must retain the single-task slot")
        } catch (_: HostBusyException) {
            assertEquals(1, capabilities.prepareDeviceCalls)
        }
        assertTrue(host.retryCleanup())
        assertEquals(HostPhase.FAILED, host.state().phase)
        assertEquals("CLEANUP_RECOVERED", host.state().code)
        assertEquals(CleanupState.PROVEN, host.state().cleanupState)
        assertEquals(2, execution.closes)
        capabilities.closeFailures = 0
        assertEquals(HostPhase.EXECUTING,
            host.submit(RawToolCall("android.device_info", emptyMap())).phase)
    }

    @Test
    fun synchronousCompletionIsSafeAndOwnerClosesExactlyOnce() {
        capabilities.completeSynchronously = ExecutionOutcome.Success(ToolResult(mapOf("apiLevel" to "36")))
        val state = host.submit(RawToolCall("android.device_info", emptyMap()))
        assertEquals(HostPhase.SUCCEEDED, state.phase)
        assertEquals(1, capabilities.executions.single().closes)
    }

    @Test
    fun adapterFailureIsBoundedAndReleasesOwner() {
        capabilities.completeSynchronously = ExecutionOutcome.Failure("BAD-code", "x".repeat(600),
            ToolResult(mapOf("sourceStatus" to "failed", "cleanupStatus" to "done")))
        val state = host.submit(RawToolCall("android.device_info", emptyMap()))
        assertEquals(HostPhase.FAILED, state.phase)
        assertEquals("ADAPTER_FAILURE", state.code)
        assertEquals(256, notNull(state.message).length)
        assertEquals("failed", state.result?.fields?.get("sourceStatus"))
        assertEquals("done", state.result?.fields?.get("cleanupStatus"))
        assertEquals(1, capabilities.executions.single().closes)
    }

    @Test
    fun closeInvalidatesCurrentTaskAndNoCallbackCanReviveIt() {
        host.submit(RawToolCall("android.device_info", emptyMap()))
        val execution = capabilities.executions.single()
        host.close()
        assertEquals(HostPhase.CLOSED, host.state().phase)
        assertEquals(1, execution.closes)
        execution.complete(ExecutionOutcome.Success(ToolResult(mapOf("late" to "true"))))
        assertEquals(HostPhase.CLOSED, host.state().phase)
    }

    @Test
    fun activeTaskRejectsConcurrentSubmission() {
        host.submit(displayTap(3, 4))
        try {
            host.submit(RawToolCall("android.device_info", emptyMap()))
            fail("Expected HostBusyException")
        } catch (_: HostBusyException) {
            assertEquals(HostPhase.PENDING_APPROVAL, host.state().phase)
        }
    }

    private fun displayObserve(id: Int, epoch: Long) = RawToolCall(
        "android.virtual_display_observe_or_demo_input",
        mapOf("action" to "observe", "expectedDisplayId" to id, "expectedDisplayEpoch" to epoch),
    )

    private fun displayTap(x: Int, y: Int, epoch: Long = 4L) = RawToolCall(
        "android.virtual_display_observe_or_demo_input",
        mapOf("action" to "demo_tap", "expectedDisplayId" to 9,
            "expectedDisplayEpoch" to epoch, "x" to x, "y" to y),
    )

    private fun displayText(text: String) = RawToolCall(
        "android.virtual_display_observe_or_demo_input",
        mapOf("action" to "demo_text", "expectedDisplayId" to 9,
            "expectedDisplayEpoch" to 4L, "text" to text),
    )

    private fun validation(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected ToolValidationException($code)")
        } catch (error: ToolValidationException) {
            assertEquals(code, error.code)
        }
    }

    private fun <T : Any> notNull(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private class FakeClock : MonotonicClock {
        var now = 1_000_000_000L
        override fun nowNanos() = now
        fun advanceMillis(value: Long) { now += TimeUnit.MILLISECONDS.toNanos(value) }
    }

    private class FakeExecution(
        private val synchronous: ExecutionOutcome?,
        private var closeFailuresRemaining: Int,
    ) : OwnedExecution {
        var starts = 0
        var closes = 0
        private var completion: ((ExecutionOutcome) -> Unit)? = null

        override fun start(completion: (ExecutionOutcome) -> Unit) {
            starts++
            this.completion = completion
            synchronous?.let(completion)
        }

        fun complete(outcome: ExecutionOutcome) { completion?.invoke(outcome) }
        override fun close() {
            closes++
            if (closeFailuresRemaining > 0) {
                closeFailuresRemaining--
                throw java.io.IOException("injected close failure")
            }
        }
    }

    private class FakeCapabilities(private val workspace: Path) : TaskCapabilities {
        var device = DeviceInfoTarget(10469)
        var linux = LinuxTarget("rootfs-7", 10469, workspace, verifiedLinuxSession = true,
            backendAlive = true)
        var display = DisplayTarget(4L, 9, 10469, backendReady = true)
        var onDisplayRead: () -> Unit = {}
        var afterFirstLinuxRead: LinuxTarget? = null
        var linuxReads = 0
        var prepareDeviceCalls = 0
        var prepareLinuxCalls = 0
        var prepareDisplayCalls = 0
        var lastCsv: Path? = null
        var lastDisplayAction: DisplayAction? = null
        var completeSynchronously: ExecutionOutcome? = null
        var closeFailures = 0
        var prepareHook: () -> Unit = {}
        val executions = mutableListOf<FakeExecution>()

        override fun currentDeviceInfoTarget() = device
        override fun currentLinuxTarget(): LinuxTarget {
            linuxReads++
            return if (linuxReads >= 2) afterFirstLinuxRead ?: linux else linux
        }
        override fun currentDisplayTarget(): DisplayTarget {
            onDisplayRead()
            return display
        }

        override fun prepareDeviceInfo(expected: DeviceInfoTarget): OwnedExecution {
            prepareDeviceCalls++
            return execution()
        }

        override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: Path): OwnedExecution {
            prepareLinuxCalls++
            lastCsv = canonicalCsv
            return execution()
        }

        override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution {
            prepareDisplayCalls++
            lastDisplayAction = action
            return execution()
        }

        private fun execution(): FakeExecution {
            val execution = FakeExecution(completeSynchronously, closeFailures)
            prepareHook()
            executions += execution
            return execution
        }
    }
}
