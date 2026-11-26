package io.github.kkkhs.pocketrun.taskhost

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ToolUseBlock
import com.anthropic.models.messages.DirectCaller
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/** Shared native policy with fake sources; actual Binder/file/model integration is separate. */
class Chapter13WorkflowTest {
    @Test fun everyCompleteStepUsesTheOriginalPhoneApprovalSlotAndNativeGoalGate() {
        val exercise = Exercise()
        exercise.use {
            exercise.step(exercise.read)
            exercise.step(exercise.observe)
            exercise.step(exercise.tap)
            exercise.step(exercise.text)
            assertEquals("text_accepted", exercise.contract.snapshot().stage)
            assertEquals(4, exercise.source.starts)
            exercise.step(exercise.observe)
            exercise.step(exercise.publish)
            assertEquals(ContextPhase.COMPLETED, exercise.task.finish(exercise.id).phase)
            assertEquals("completed", exercise.contract.snapshot().phase)
            assertEquals(exercise.report.fileText, String(exercise.source.output!!, Charsets.UTF_8))
            assertEquals(6, exercise.task.state().steps.size)
            assertTrue(exercise.task.state().steps.all { step ->
                step.status.approvalState == ApprovalState.APPROVED_BY_APP_UI && step.status.cleanupState == CleanupState.PROVEN
            })
            assertEquals(6, exercise.task.state().steps.map { step -> step.status.requestNonce }.distinct().size)
            // Completing the named exercise does not change the old direct tool's behavior.
            assertEquals(HostPhase.SUCCEEDED, exercise.task.withoutActiveTask {
                exercise.host.submit(RawToolCall("android.device_info", emptyMap()))
            }.phase)
        }
    }

    @Test fun manualFinishAfterOnlyReadingStopsRatherThanClaimingSuccess() {
        Exercise().use { exercise ->
            exercise.step(exercise.read)
            assertEquals(ContextPhase.FAILED, exercise.task.finish(exercise.id).phase)
            assertEquals("NATIVE_GOAL_UNVERIFIED", exercise.task.state().code)
            assertEquals("failed", exercise.contract.snapshot().phase)
            assertNull(exercise.source.output)
            assertEquals(1, exercise.source.starts)
        }
    }

    @Test fun directEntryCannotPublishWithoutTheNativeCompleteContract() {
        val source = Source()
        TaskHost(source).use { host ->
            val error = assertThrows(ToolValidationException::class.java) {
                host.submit(RawToolCall("workspace.publish_demo_result", mapOf(
                    "text" to source.report.text, "expectedInputDigest" to source.report.inputDigest)))
            }
            assertEquals("COMPLETE_TASK_REQUIRED", error.code)
            assertEquals(0, source.starts)
            assertNull(source.output)
        }
    }

    @Test fun changingTheInputBeforeApprovalInvalidatesTheOriginalRequest() {
        Exercise().use { exercise ->
            val pending = exercise.task.submit(exercise.id, exercise.read)
            val nonce = pending.steps.single().status.requestNonce!!
            exercise.source.digest = "f".repeat(64)
            exercise.host.onAppUiDecision(nonce, AppUiDecision.APPROVE)
            assertEquals(ContextPhase.FAILED, exercise.task.state().phase)
            assertEquals("INPUT_CHANGED", exercise.task.state().code)
            assertEquals(0, exercise.source.starts)
            assertFalse(exercise.host.onAppUiDecision(nonce, AppUiDecision.APPROVE))
        }
    }

    @Test fun journalWriteConsumingTheDeadlineCannotInjectInput() {
        Exercise().use { exercise ->
            exercise.step(exercise.read)
            exercise.step(exercise.observe)
            exercise.onPersist = { record ->
                if (record.pendingEffect == "tap") exercise.now += TimeUnit.SECONDS.toNanos(6)
            }
            val pending = exercise.task.submit(exercise.id, exercise.tap)
            exercise.host.onAppUiDecision(pending.steps.last().status.requestNonce!!, AppUiDecision.APPROVE)
            assertEquals(HostPhase.EXPIRED, exercise.host.state().phase)
            assertEquals(0, exercise.source.taps)
            assertEquals("tap", exercise.contract.snapshot().pendingEffect)
        }
    }

    @Test fun unprovenOwnerSurvivesContextCancellationAndBlocksFreshGoals() {
        Exercise().use { exercise ->
            exercise.source.failClose = true
            val pending = exercise.task.submit(exercise.id, exercise.read)
            exercise.host.onAppUiDecision(pending.steps.single().status.requestNonce!!, AppUiDecision.APPROVE)
            assertEquals(CleanupState.UNPROVEN, exercise.host.state().cleanupState)
            exercise.task.cancel(exercise.id)
            assertThrows(HostBusyException::class.java) { exercise.task.begin("ordinary next goal") }
            assertEquals("read", exercise.contract.snapshot().pendingEffect)
            exercise.source.failClose = false
            assertTrue(exercise.host.retryCleanup())
            assertEquals(ContextPhase.FAILED, exercise.task.state().phase)
            assertNotEquals("completed", exercise.contract.snapshot().phase)
        }
    }

    @Test fun modelModesKeepNewDisclosureOutOfTheOriginalToolSurface() {
        val call = ToolUseBlock.builder().id("call_demo_observe").name("android_virtual_display").caller(DirectCaller.builder().build())
            .input(JsonValue.from(mapOf("action" to "observe_demo", "expectedDisplayId" to 7,
                "expectedDisplayEpoch" to 3))).build()
        assertThrows(IllegalArgumentException::class.java) { ModelToolSurface.parse(call) }
        assertEquals(PocketRunTool.ANDROID_VIRTUAL_DISPLAY.wireName, ModelToolSurface.parse(call, complete = true).tool)
        assertFalse(ModelToolSurface.tools().any { it.name().startsWith("workspace_") })
        assertEquals(3, ModelToolSurface.tools(complete = true).size)
        val step = ContextStep(1, RawToolCall(PocketRunTool.ANDROID_VIRTUAL_DISPLAY.wireName, mapOf("action" to "observe_demo")),
            HostStatus(HostPhase.SUCCEEDED, cleanupState = CleanupState.PROVEN,
                result = ToolResult(mapOf("demoValue" to "private-demo", "demoResult" to "input: private-demo",
                    "unrelatedUiText" to "other-app-private-data"))))
        assertFalse(ModelToolSurface.summary(step).contains("private-demo"))
        assertTrue(ModelToolSurface.summary(step, complete = true).contains("private-demo"))
        assertFalse(ModelToolSurface.summary(step, complete = true).contains("other-app-private-data"))
    }

    private class Exercise : AutoCloseable {
        var now = 0L
        val source = Source()
        val report = source.report
        val host = TaskHost(source, MonotonicClock { now }, NonceSource { "abcdefghijklmnop" })
        val task = SingleTaskContext(host, MonotonicClock { now })
        var onPersist: (TaskJournalRecord) -> Unit = {}
        val contract = CompleteTaskContract(TaskJournalRecord(TaskJournalCodec.newRunId(), "Complete demo",
            inputDigest = report.inputDigest), source.target, source::currentWorkspaceTarget,
            { source.currentDemoObservation(source.target) }, source::currentDemoResult) { onPersist(it) }
        val id = task.beginComplete("Complete demo", contract).taskId!!
        val read = RawToolCall("workspace.read_demo_csv", emptyMap())
        val observe = display("observe_demo")
        val tap = display("demo_tap", mapOf("x" to source.demo.x, "y" to source.demo.y))
        val text = display("demo_text", mapOf("text" to report.text))
        val publish = RawToolCall("workspace.publish_demo_result", mapOf("text" to report.text,
            "expectedInputDigest" to report.inputDigest))
        private fun display(action: String, arguments: Map<String, Any?> = emptyMap()) = RawToolCall(PocketRunTool.ANDROID_VIRTUAL_DISPLAY.wireName,
            mapOf("action" to action, "expectedDisplayId" to source.target.displayId,
                "expectedDisplayEpoch" to source.target.displayEpoch) + arguments)
        fun step(call: RawToolCall) {
            val starts = source.starts
            val pending = task.submit(id, call)
            assertEquals(HostPhase.PENDING_APPROVAL, pending.steps.last().status.phase)
            assertEquals(starts, source.starts)
            assertTrue(host.onAppUiDecision(pending.steps.last().status.requestNonce!!, AppUiDecision.APPROVE))
            assertEquals(ContextPhase.READY, task.state().phase)
        }
        override fun close() { source.failClose = false; host.retryCleanup(); task.close() }
    }

    private class Source : TaskCapabilities {
        val csv = "name,quantity\napple,2\nbanana,3\n"
        val report = DemoCsvReport.parse(csv.toByteArray(Charsets.UTF_8))
        var digest = report.inputDigest
        val target = DisplayTarget(3, 7, 10001, true)
        var demo = DemoObservation(target, "", "input: ", 10, 20, 200, 80, false)
        var output: ByteArray? = null
        var starts = 0
        var taps = 0
        var failClose = false
        override fun currentDeviceInfoTarget() = DeviceInfoTarget(10001)
        override fun currentLinuxTarget() = throw UnsupportedOperationException()
        override fun currentDisplayTarget() = target
        override fun currentWorkspaceTarget() = WorkspaceTarget(10001, Paths.get("/task-owned/private/demo"), digest)
        override fun currentDemoObservation(expected: DisplayTarget) = demo
        override fun currentDemoResult() = output
        override fun prepareDeviceInfo(expected: DeviceInfoTarget) = execution { ToolResult(mapOf("apiLevel" to "36")) }
        override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: Path) = throw UnsupportedOperationException()
        override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction) = throw UnsupportedOperationException()
        override fun prepareReadDemoCsv(expected: WorkspaceTarget) = execution {
            ToolResult(mapOf("source" to "app-private-workspace", "csvText" to csv, "inputDigest" to digest))
        }
        override fun preparePublishDemoResult(expected: WorkspaceTarget, text: String, guard: () -> Unit) = execution {
            guard()
            output = (text + "\n").toByteArray(Charsets.UTF_8)
            ToolResult(mapOf("publicationStatus" to "published", "outputDigest" to DemoCsvReport.digest(output!!)))
        }
        override fun prepareCompleteDisplay(expected: DisplayTarget, action: DisplayAction,
            skipInput: () -> Boolean) = execution {
            val skip = skipInput()
            when (action) {
                DisplayAction.ObserveDemo -> ToolResult(OwnedDemoObservation.fields(demo))
                is DisplayAction.DemoTap -> {
                    if (skip) ToolResult(OwnedDemoObservation.fields(demo) + ("effect" to "already_present")) else {
                        taps++; demo = demo.copy(focused = true); ToolResult(mapOf("action" to "demo_tap"))
                    }
                }
                is DisplayAction.DemoText -> {
                    if (skip) ToolResult(OwnedDemoObservation.fields(demo) + ("effect" to "already_present")) else {
                        demo = demo.copy(value = action.text, resultValue = "input: ${action.text}")
                        ToolResult(mapOf("action" to "demo_text"))
                    }
                }
                else -> throw UnsupportedOperationException()
            }
        }
        private fun execution(action: () -> ToolResult) = object : OwnedExecution {
            override fun start(completion: (ExecutionOutcome) -> Unit) {
                starts++
                val outcome = try { ExecutionOutcome.Success(action()) }
                catch (_: Exception) { ExecutionOutcome.Failure("TEST_SOURCE_GUARD_FAILED", "Fake source guard rejected input.") }
                completion(outcome)
            }
            override fun close() { check(!failClose) { "Fake close failure" } }
        }
    }
}
