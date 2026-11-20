package io.github.kkkhs.pocketrun.taskhost

import io.github.kkkhs.pocketrun.LinuxSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidTaskCapabilitiesPolicyTest {
    @Test
    fun verifiedLinuxResultPublishesActualSourceAndCleanupSeparately() {
        val result = linuxResult(status = "completed", cleanup = "done")
        val outcome = AndroidTaskCapabilities.linuxOutcome(result)
        assertTrue(outcome is ExecutionOutcome.Success)
        val fields = (outcome as ExecutionOutcome.Success).result.fields
        assertEquals("completed", fields["sourceStatus"])
        assertEquals("done", fields["cleanupStatus"])
        assertEquals("3", fields["lineCount"])
        assertEquals("10469", fields["packageUid"])
    }

    @Test
    fun cleanupUnprovenIsFailureButRetainsActualSourceFields() {
        val outcome = AndroidTaskCapabilities.linuxOutcome(
            linuxResult(status = "completed", cleanup = "unproven"))
        assertTrue(outcome is ExecutionOutcome.Failure)
        outcome as ExecutionOutcome.Failure
        assertEquals("LINUX_SOURCE_FAILED", outcome.code)
        assertEquals("completed", outcome.result?.fields?.get("sourceStatus"))
        assertEquals("unproven", outcome.result?.fields?.get("cleanupStatus"))
    }

    @Test
    fun failedOrMalformedLinuxResultCannotBecomeSuccess() {
        val failed = AndroidTaskCapabilities.linuxOutcome(
            linuxResult(status = "failed", cleanup = "done"))
        assertTrue(failed is ExecutionOutcome.Failure)
        val wrongUid = AndroidTaskCapabilities.linuxOutcome(
            linuxResult(status = "completed", cleanup = "done", guestUid = 2000))
        assertTrue(wrongUid is ExecutionOutcome.Failure)
    }

    @Test
    fun hostJvmFakeNormalDisplayClosePreservesEpochAndScreenshotUri() {
        val executor = Executors.newSingleThreadExecutor()
        val port = RecordingDisplayPort()
        val execution = AndroidTaskCapabilities.DisplayExecution(
            port, port.target, DisplayAction.Observe, executor)
        val completed = CountDownLatch(1)
        var outcome: ExecutionOutcome? = null
        try {
            execution.start { outcome = it; completed.countDown() }
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            execution.close()
            assertEquals(0, port.invalidations)
            assertEquals(port.target, port.snapshot())
            assertEquals("file:///app/cache/taskhost-frame.jpg",
                (outcome as ExecutionOutcome.Success).result.fields["uri"])
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun hostJvmFakeInflightDisplayCancellationInvalidatesExactlyOnce() {
        val executor = Executors.newSingleThreadExecutor()
        val port = RecordingDisplayPort(blockObserve = true)
        val execution = AndroidTaskCapabilities.DisplayExecution(
            port, port.target, DisplayAction.Observe, executor)
        val completed = CountDownLatch(1)
        try {
            execution.start { completed.countDown() }
            assertTrue(port.entered.await(2, TimeUnit.SECONDS))
            execution.close()
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            assertEquals(1, port.invalidations)
        } finally {
            executor.shutdownNow()
        }
    }

    private class RecordingDisplayPort(private val blockObserve: Boolean = false) : TaskHostDisplayPort {
        val target = DisplayTarget(7L, 11, 10469, true)
        val entered = CountDownLatch(1)
        private val release = CountDownLatch(if (blockObserve) 1 else 0)
        var invalidations = 0

        override fun snapshot() = target
        override fun lastKnownTarget() = target
        override fun observe(expected: DisplayTarget): ToolResult {
            check(expected == target)
            entered.countDown()
            check(release.await(2, TimeUnit.SECONDS))
            return ToolResult(mapOf("displayId" to "11",
                "uri" to "file:///app/cache/taskhost-frame.jpg"))
        }
        override fun demoTap(expected: DisplayTarget, x: Int, y: Int) = ToolResult(emptyMap())
        override fun demoText(expected: DisplayTarget, text: String) = ToolResult(emptyMap())
        override fun invalidate(expected: DisplayTarget) {
            check(expected == target)
            invalidations++
            release.countDown()
        }
        override fun setDeathListener(listener: ((DisplayTarget) -> Unit)?) = Unit
    }

    private fun linuxResult(
        status: String,
        cleanup: String,
        guestUid: Int = 10469,
    ) = LinuxSession.Result(
        token = "taskhost-count-1",
        installationGeneration = "gen-1",
        status = status,
        stdout = "3.22.8\n/workspace\n10469\n3\nPR_EXITS:0,0,0,0,0\n",
        stderr = "",
        exitCodes = listOf(0),
        stageExitCodes = listOf(0, 0, 0, 0, 0),
        durationMillis = 20,
        appUid = 10469,
        alpineVersion = "3.22.8",
        guestUid = guestUid,
        cwd = "/workspace",
        lineCount = 3,
        cleanupStatus = cleanup,
    )
}
