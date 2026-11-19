package io.github.kkkhs.pocketrun

import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PtyModulePolicyTest {
    @Test fun fixedThreeMethodPolicyAcceptsOnlyTokenAndIntegerTerminalDimensions() {
        assertEquals(80 to 24, PtyModule.validateStart("pty-1", 80.0, 24.0))
        expectFailure { PtyModule.validateStart("bad/token", 80.0, 24.0) }
        expectFailure { PtyModule.validateStart("x".repeat(65), 80.0, 24.0) }
        expectFailure { PtyModule.validateStart("pty", 15.0, 24.0) }
        expectFailure { PtyModule.validateStart("pty", 80.5, 24.0) }
        expectFailure { PtyModule.validateStart("pty", Double.NaN, 24.0) }
        expectFailure { PtyModule.validateStart("pty", 80.0, 401.0) }
    }

    @Test fun textIsUtf8BoundedAndMutuallyExclusiveWithFixedControlCharacters() {
        val text = PtyModule.interaction("x".repeat(PtySession.MAX_INPUT_BYTES), "")
        assertEquals(PtySession.MAX_INPUT_BYTES, text.first!!.size)
        assertNull(text.second)
        expectFailure { PtyModule.interaction("😀".repeat(1025), "") }
        expectFailure { PtyModule.interaction("text", "interrupt") }
        expectFailure { PtyModule.interaction("", "") }
        expectFailure { PtyModule.interaction("", "kill") }
        assertEquals(PtySession.Control.INTERRUPT, PtyModule.interaction("", "interrupt").second)
        assertEquals(PtySession.Control.END_OF_INPUT, PtyModule.interaction("", "eof").second)
        assertEquals(PtySession.Control.SUSPEND, PtyModule.interaction("", "suspend").second)
        assertEquals(PtySession.Control.QUIT, PtyModule.interaction("", "quit").second)
    }

    @Test fun moduleOutputDefenseKeepsUtf8BoundaryAndLifetimeAt64KiB() {
        val output = StringBuilder()
        var bytes = PtyModule.appendBounded(output, 0, "😀".repeat(PtyModule.MAX_OUTPUT_BYTES / 4))
        assertEquals(PtyModule.MAX_OUTPUT_BYTES, bytes)
        assertEquals(PtyModule.MAX_OUTPUT_BYTES, output.toString().toByteArray().size)
        bytes = PtyModule.appendBounded(output, bytes, "not appended")
        assertEquals(PtyModule.MAX_OUTPUT_BYTES, bytes)
        assertFalse(output.endsWith("not appended"))

        val partial = StringBuilder()
        val used = PtyModule.appendBounded(partial, PtyModule.MAX_OUTPUT_BYTES - 3, "x😀")
        assertEquals(PtyModule.MAX_OUTPUT_BYTES - 2, used)
        assertEquals("x", partial.toString())
    }

    @Test fun fixedDeadlineCanBeShortenedOnlyInternallyAndTimeoutWinsOverCancellation() {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        try {
            val fired = CountDownLatch(1)
            PtyModule.scheduleTimeout(scheduler, 20) { fired.countDown() }
            assertTrue(fired.await(1, TimeUnit.SECONDS))
            assertEquals("timed_out", PtyModule.classifyStatus(timedOut = true, cancelled = true, exit = null))
            assertEquals("cancelled", PtyModule.classifyStatus(timedOut = false, cancelled = true, exit = null))

            val cancelled = CountDownLatch(1)
            val future = PtyModule.scheduleTimeout(scheduler, 40) { cancelled.countDown() }
            assertTrue(future.cancel(false))
            assertFalse(cancelled.await(100, TimeUnit.MILLISECONDS))
            expectFailure { PtyModule.scheduleTimeout(scheduler, PtyModule.DEFAULT_TIMEOUT_MS + 1) {} }
        } finally { scheduler.shutdownNow() }
    }

    @Test fun cleanupFailureAlwaysCarriesMachineCodeCompatibleMarker() {
        assertEquals("cleanup_unproven: ordinary open I/O", PtyModule.cleanupFailureMessage(IOException("ordinary open I/O")))
        assertEquals("cleanup_unproven: already marked",
            PtyModule.cleanupFailureMessage(IOException("cleanup_unproven: already marked")))
    }

    @Test fun onlyNoSuchFileIsCleanupAbsenceWhileAccessAndIoStayUnproven() {
        val fixture = File("/private/pocketrun-run")
        assertTrue(PtyModule.isGone(fixture) { throw NoSuchFileException(fixture.path) })
        try {
            PtyModule.isGone(fixture) { throw AccessDeniedException(fixture.path) }
            fail("access denied must not become gone")
        } catch (_: AccessDeniedException) {}
        assertFalse(PtyModule.isGone(fixture) {})
    }

    @Test fun runtimeBoundaryMatchesPinnedAndroidArm64PtyBinary() {
        assertFalse(PtyModule.supportsRuntime(33, true, true))
        assertFalse(PtyModule.supportsRuntime(34, false, true))
        assertFalse(PtyModule.supportsRuntime(34, true, false))
        assertTrue(PtyModule.supportsRuntime(34, true, true))
    }

    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("expected failure") } catch (_: IllegalArgumentException) {}
    }
}
