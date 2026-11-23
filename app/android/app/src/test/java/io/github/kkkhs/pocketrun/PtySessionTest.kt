package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PtySessionTest {
    private class FakeBackend : PtyBackend {
        var nextHandle = 40L
        var opened = 0
        var terminated = 0
        var closed = 0
        var lastWrite = ByteArray(0)
        var lastSize = 0 to 0
        var lastSignal = 0
        var exit = PtyExit(PtyExit.Kind.EXITED, 0, 1234, true)
        var closeProven = true
        var requireDrainBeforeWait = false
        val reads = ArrayDeque<ByteArray>()
        var enteredOpen: CountDownLatch? = null
        var releaseOpen: CountDownLatch? = null
        var enteredRead: CountDownLatch? = null
        var releaseRead: CountDownLatch? = null
        var removeHandleOnClose = false
        @Volatile var handleGone = false

        override fun open(config: PtyTrustedConfig): Long {
            enteredOpen?.countDown()
            releaseOpen?.await(2, TimeUnit.SECONDS)
            opened++
            return ++nextHandle
        }
        override fun read(handle: Long, maximumBytes: Int): ByteArray? {
            enteredRead?.countDown()
            releaseRead?.await(2, TimeUnit.SECONDS)
            return if (reads.isEmpty()) null else reads.removeFirst()
        }
        override fun write(handle: Long, bytes: ByteArray): Int { lastWrite = bytes.copyOf(); return bytes.size }
        override fun resize(handle: Long, columns: Int, rows: Int) { lastSize = columns to rows }
        override fun signalOwned(handle: Long, signal: Int) { lastSignal = signal }
        override fun terminateRoot(handle: Long): Boolean { terminated++; return true }
        override fun waitForExit(handle: Long): PtyExit {
            if (handleGone) throw IllegalStateException("native handle already removed")
            if (requireDrainBeforeWait && reads.isNotEmpty()) throw IOException("simulated full PTY backpressure")
            return exit
        }
        override fun close(handle: Long): Boolean {
            closed++
            if (removeHandleOnClose) { handleGone = true; releaseRead?.countDown() }
            return closeProven
        }
    }

    @Test fun oneOwnedSessionEnforcesBusyTokenEpochAndQueuedCancellation() = temporary { files ->
        val backend = FakeBackend()
        val session = PtySession(backend)
        val first = session.open("pty-1", config(files)) {}
        expectFailure { session.open("pty-2", config(files)) {} }
        expectFailure { session.open("bad/token", config(files)) {} }
        assertFalse(session.cancel("different"))
        assertTrue(session.cancel("pty-1"))
        assertEquals(1, backend.terminated)
        assertEquals(1, backend.closed)
        assertFalse(session.cancel("pty-1"))

        val second = session.open("pty-2", config(files)) {}
        expectFailure { session.write(first, byteArrayOf(1)) }
        expectFailure { session.write(PtySession.Operation(second.token, first.epoch), byteArrayOf(1)) }
        assertTrue(session.cancel(second.token))
        session.close()
        expectFailure { session.open("after-close", config(files)) {} }
    }

    @Test fun fixedInputResizeControlAndSignalPoliciesReachOnlyTheOwnedHandle() = temporary { files ->
        val backend = FakeBackend()
        PtySession(backend).use { session ->
            val operation = session.open("io", config(files)) {}
            assertEquals(3, session.write(operation, "abc".toByteArray()))
            assertArrayEquals("abc".toByteArray(), backend.lastWrite)
            assertEquals(1, session.sendControl(operation, PtySession.Control.INTERRUPT))
            assertArrayEquals(byteArrayOf(3), backend.lastWrite)
            session.resize(operation, 16, 400)
            assertEquals(16 to 400, backend.lastSize)
            session.signal(operation, PtySession.Signal.SUSPEND)
            assertEquals(20, backend.lastSignal)
            expectFailure { session.write(operation, ByteArray(PtySession.MAX_INPUT_BYTES + 1)) }
            expectFailure { session.resize(operation, 15, 80) }
        }
        assertEquals(1, backend.terminated)
        assertEquals(1, backend.closed)
    }

    @Test fun waitDrainsMoreThanPtyCapacityBeforeWaitingAndCapsAllCallbacksAt64KiB() = temporary { files ->
        val backend = FakeBackend().apply { requireDrainBeforeWait = true }
        repeat(9) { backend.reads.add(ByteArray(PtyCodec.MAX_READ_BYTES) { 'x'.code.toByte() }) }
        val output = StringBuilder()
        val session = PtySession(backend)
        val operation = session.open("drain", config(files)) { output.append(it) }
        val exit = session.waitForExit(operation)
        assertEquals(PtyExit.Kind.EXITED, exit.kind)
        assertEquals(0, exit.code)
        assertTrue(exit.rootReaped)
        assertEquals(PtyCodec.MAX_OUTPUT_BYTES, output.toString().toByteArray().size)
        assertEquals(1, backend.closed)
        assertFalse(session.cancel(operation.token))
        session.close()
        assertEquals(0, backend.terminated)
    }

    @Test fun cancelDuringNativeOpenKeepsBusyThenCleansReturnedHandleWithoutPublishingIt() = temporary { files ->
        val backend = FakeBackend().apply {
            enteredOpen = CountDownLatch(1)
            releaseOpen = CountDownLatch(1)
        }
        val session = PtySession(backend)
        val failure = AtomicReference<Throwable?>()
        val thread = Thread {
            try { session.open("opening", config(files)) {} }
            catch (error: Throwable) { failure.set(error) }
        }
        thread.start()
        assertTrue(backend.enteredOpen!!.await(1, TimeUnit.SECONDS))
        assertTrue(session.cancel("opening"))
        expectFailure { session.open("too-early", config(files)) {} }
        backend.releaseOpen!!.countDown()
        thread.join(2000)
        assertFalse(thread.isAlive)
        assertTrue(failure.get() is java.util.concurrent.CancellationException)
        assertEquals(1, backend.terminated)
        assertEquals(1, backend.closed)
        val next = session.open("next", config(files)) {}
        assertTrue(session.cancel(next.token))
    }

    @Test fun postExitDrainCountsBytesRatherThanReadCallsForOneByteChunks() = temporary { files ->
        val backend = FakeBackend().apply {
            requireDrainBeforeWait = true
            repeat(257) { reads.add(byteArrayOf('z'.code.toByte())) }
        }
        val output = StringBuilder()
        val session = PtySession(backend)
        val operation = session.open("small-post-exit", config(files)) { output.append(it) }
        assertEquals(0, session.waitForExit(operation).code)
        assertEquals("z".repeat(257), output.toString())
        session.close()
    }

    @Test fun waitCannotPublishAReusableSlotWhenNativeCloseIsUnproven() = temporary { files ->
        val backend = FakeBackend().apply { closeProven = false }
        val session = PtySession(backend)
        val operation = session.open("wait-retain", config(files)) {}
        try { session.waitForExit(operation); fail("unproven wait cleanup must fail") }
        catch (error: IOException) { assertTrue(error.message!!.startsWith("cleanup_unproven:")) }
        expectFailure { session.open("must-remain-busy", config(files)) {} }
        backend.closeProven = true
        assertTrue(session.cancel(operation.token))
    }

    @Test fun unprovenCloseRetainsBusyOwnershipUntilSameTokenRetrySucceeds() = temporary { files ->
        val backend = FakeBackend().apply { closeProven = false }
        val session = PtySession(backend)
        val operation = session.open("retain", config(files)) {}
        try { session.cancel(operation.token); fail("unproven cleanup must fail") }
        catch (error: IOException) { assertTrue(error.message!!.startsWith("cleanup_unproven:")) }
        expectFailure { session.open("must-stay-busy", config(files)) {} }
        backend.closeProven = true
        assertTrue(session.cancel(operation.token))
        val next = session.open("after-proof", config(files)) {}
        assertTrue(session.cancel(next.token))
    }

    @Test fun cancelWinningBlockedReadConvertsRemovedNativeHandleErrorToCancellation() = temporary { files ->
        val backend = FakeBackend().apply {
            enteredRead = CountDownLatch(1)
            releaseRead = CountDownLatch(1)
            removeHandleOnClose = true
        }
        val session = PtySession(backend)
        val operation = session.open("blocked-read", config(files)) {}
        val failure = AtomicReference<Throwable?>()
        val waiter = Thread {
            try { session.waitForExit(operation); fail("cancelled wait must not publish exit") }
            catch (error: Throwable) { failure.set(error) }
        }
        waiter.start()
        assertTrue(backend.enteredRead!!.await(1, TimeUnit.SECONDS))
        assertTrue(session.cancel(operation.token))
        waiter.join(2000)
        assertFalse(waiter.isAlive)
        val cancelled = failure.get()
        assertTrue(cancelled is java.util.concurrent.CancellationException)
        assertTrue(cancelled!!.cause is IllegalStateException)
        assertTrue(backend.handleGone)
        assertEquals(2, backend.closed) // cancel close plus the waiter's idempotent cleanup close.
    }

    @Test fun trustedConfigurationRejectsPathsOutsideTheAppFilesTree() = temporary { files ->
        val native = File(files, "native").apply { mkdir() }
        val root = File(files, "linux/rootfs").apply { mkdirs() }
        val workspace = File(files, "workspaces/demo").apply { mkdirs() }
        val run = File(files, "linux/runs/one").apply { mkdirs(); File(this, "tmp").mkdir() }
        val accepted = PtyTrustedConfig.create(files, native, root, workspace, run, 10469, 12345)
        assertEquals(File(run, "tmp").canonicalFile, accepted.temporaryDirectory)
        val sibling = Files.createTempDirectory("pocketrun-pty-escape").toFile()
        try { expectFailure { PtyTrustedConfig.create(files, native, sibling, workspace, run, 10469, 12345) } }
        finally { sibling.deleteRecursively() }
        expectFailure { PtyTrustedConfig.create(files, native, root, workspace, run, 0, 12345) }
    }

    private fun config(files: File): PtyTrustedConfig {
        val native = File(files, "native").apply { mkdirs() }
        val root = File(files, "linux/rootfs").apply { mkdirs() }
        val workspace = File(files, "workspaces/demo").apply { mkdirs() }
        val run = File(files, "linux/runs/test-${System.nanoTime()}").apply { mkdirs(); File(this, "tmp").mkdir() }
        return PtyTrustedConfig.create(files, native, root, workspace, run, 10469, 12345)
    }

    private fun expectFailure(action: () -> Unit) {
        try { action(); fail("expected failure") } catch (_: IllegalArgumentException) {}
        catch (_: IllegalStateException) {} catch (_: IOException) {}
    }

    private fun temporary(action: (File) -> Unit) {
        val directory = Files.createTempDirectory("pocketrun-pty-jvm").toFile()
        try { action(directory) } finally { directory.deleteRecursively() }
    }
}
