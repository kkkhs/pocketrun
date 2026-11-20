package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local fake typed port; runs the production display execution and death binding, not a device. */
class TaskContextDisplayIntegrationTest {
    @Test fun unregisterWhilePendingInvalidatesContextAndRejectsOldApproval() {
        Fixture().use { f ->
            val id = f.task.begin("observe demo").taskId!!
            val pending = f.task.submit(id, f.demoText())
            val nonce = pending.steps.single().status.requestNonce!!
            f.installation.close()
            assertFalse(f.host.onAppUiDecision(nonce, AppUiDecision.APPROVE))
            assertEquals("BACKEND_DEATH", f.task.state().code)
            assertEquals(ContextPhase.FAILED, f.task.state().phase)
            assertEquals(CleanupState.NONE, f.task.state().steps.single().status.cleanupState)
            assertEquals(0, f.prepares)
            assertEquals(0, f.port.observations)
            assertEquals(0, f.port.inputs)
            assertThrows(IllegalStateException::class.java) { f.task.submit(id, f.observe()) }
        }
    }

    @Test fun changedEpochBeforeApprovalStopsWithoutPreparingOrReplaying() {
        Fixture().use { f ->
            val id = f.task.begin("observe demo").taskId!!
            val pending = f.task.submit(id, f.demoText())
            f.port.target = f.port.target.copy(displayEpoch = 8L)
            f.host.onAppUiDecision(pending.steps.single().status.requestNonce!!, AppUiDecision.APPROVE)
            assertEquals(ContextPhase.FAILED, f.task.state().phase)
            assertEquals("TARGET_CHANGED", f.task.state().code)
            assertEquals(0, f.prepares)
            assertEquals(0, f.port.observations)
            assertEquals(0, f.port.inputs)
        }
    }

    @Test fun successfulDisplayRetainsFrameAndOldDeathCannotInvalidateNewEpoch() {
        Fixture().use { f ->
            var id = f.task.begin("observe demo").taskId!!
            val oldTarget = f.port.target
            f.task.submit(id, f.observe())
            f.worker.submit {}.get(5, TimeUnit.SECONDS)
            val done = f.task.finish(id)
            assertEquals(ContextPhase.COMPLETED, done.phase)
            assertEquals("file:///synthetic-frame.png", done.steps.single().status.result!!.fields["screenshotUri"])
            assertEquals(CleanupState.PROVEN, done.steps.single().status.cleanupState)
            assertEquals(0, f.port.invalidations)
            assertEquals(oldTarget, f.port.snapshot())

            f.port.target = oldTarget.copy(displayEpoch = 8L)
            id = f.task.begin("new epoch").taskId!!
            val pending = f.task.submit(id, f.demoText())
            f.port.emitDeath(oldTarget)
            assertEquals(HostPhase.PENDING_APPROVAL, f.task.state().steps.single().status.phase)
            assertTrue(f.host.onAppUiDecision(pending.steps.single().status.requestNonce!!, AppUiDecision.APPROVE))
            f.worker.submit {}.get(5, TimeUnit.SECONDS)
            assertEquals(ContextPhase.COMPLETED, f.task.finish(id).phase)
            assertEquals(1, f.port.observations)
            assertEquals(1, f.port.inputs)
            assertEquals(0, f.port.invalidations)
        }
    }

    private class Fixture : Closeable {
        val worker = Executors.newSingleThreadExecutor()
        val port = RecordingPort()
        var prepares = 0
        private val source = object : TaskCapabilities {
            override fun currentDeviceInfoTarget() = DeviceInfoTarget(10469, false)
            override fun currentLinuxTarget(): LinuxTarget = error("No Linux source in this fixture")
            override fun currentDisplayTarget() = TaskHostDisplayPortRegistry.current()?.snapshot()
                ?: port.target.copy(backendReady = false)
            override fun prepareDeviceInfo(expected: DeviceInfoTarget): OwnedExecution = error("No device source")
            override fun prepareCountDemoCsv(expected: LinuxTarget, canonicalCsv: java.nio.file.Path): OwnedExecution =
                error("No Linux source")
            override fun prepareDisplay(expected: DisplayTarget, action: DisplayAction): OwnedExecution {
                prepares++
                return AndroidTaskCapabilities.DisplayExecution(
                    checkNotNull(TaskHostDisplayPortRegistry.current()), expected, action, worker)
            }
        }
        val host = TaskHost(source)
        val task = SingleTaskContext(host)
        private val listener = TaskHostDisplayPortRegistry.addDeathListener { target ->
            TaskHostBackendDeath.binding(host.state(), target)?.let {
                host.onBackendDeath(it.requestNonce, it.backendEpoch)
            }
        }
        val installation = TaskHostDisplayPortRegistry.install(port)
        fun observe() = RawToolCall(PocketRunTool.ANDROID_VIRTUAL_DISPLAY.wireName, mapOf(
            "action" to "observe", "expectedDisplayId" to port.target.displayId,
            "expectedDisplayEpoch" to port.target.displayEpoch))
        fun demoText() = observe().let { it.copy(arguments = it.arguments + mapOf(
            "action" to "demo_text", "text" to "synthetic")) }
        override fun close() {
            listener.close()
            task.close()
            installation.close()
            worker.shutdownNow()
            check(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private class RecordingPort : TaskHostDisplayPort {
        var target = DisplayTarget(7L, 11, 10469, true)
        var observations = 0
        var inputs = 0
        var invalidations = 0
        private var listener: ((DisplayTarget) -> Unit)? = null
        override fun snapshot() = target
        override fun lastKnownTarget() = target
        override fun observe(expected: DisplayTarget): ToolResult {
            check(expected == target)
            observations++
            return ToolResult(mapOf("source" to "app-owned-typed-binder", "displayId" to "11",
                "displayEpoch" to target.displayEpoch.toString(), "screenshotUri" to "file:///synthetic-frame.png"))
        }
        override fun demoTap(expected: DisplayTarget, x: Int, y: Int): ToolResult = error("No input in fixture")
        override fun demoText(expected: DisplayTarget, text: String): ToolResult {
            check(expected == target)
            inputs++
            return ToolResult(mapOf("source" to "app-owned-typed-binder", "action" to "demo-text",
                "displayId" to target.displayId.toString(), "displayEpoch" to target.displayEpoch.toString()))
        }
        override fun invalidate(expected: DisplayTarget) { invalidations++ }
        override fun setDeathListener(listener: ((DisplayTarget) -> Unit)?) { this.listener = listener }
        fun emitDeath(target: DisplayTarget) { listener?.invoke(target) }
    }
}
