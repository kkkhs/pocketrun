package io.github.kkkhs.pocketrun

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real App-UID PTY/PRoot resource tests. Host execution is separate and must not be inferred here. */
@RunWith(AndroidJUnit4::class)
class Chapter8LifecycleIntegrationTest {
    @Test fun normalExitReapsBackgroundAndSetsidTreeBeforeRunLeaseCleanup() = Chapter7TestSupport.inFiles { files ->
        PtyTree(files).use { tree ->
            tree.start(Mode.NORMAL)
            val identities = tree.capture()
            tree.openGate()
            val exit = tree.exit()
            assertEquals(PtyExit.Kind.EXITED, exit.kind)
            assertEquals(0, exit.code)
            assertTrue(exit.rootReaped)
            assertTrue(tree.output().toByteArray(Charsets.UTF_8).size <= PtyCodec.MAX_OUTPUT_BYTES)
            tree.proveAndClose(identities)
            tree.evidence("normal-exit", exit, identities)
        }
    }

    @Test fun cancelWakesBlockingReadAndReapsOwnedSetsidTree() = Chapter7TestSupport.inFiles { files ->
        PtyTree(files).use { tree ->
            tree.start(Mode.HOLD)
            val identities = tree.capture()
            assertTrue(tree.cancel())
            val failure = assertThrows(ExecutionException::class.java) { tree.rawExit() }
            assertTrue(failure.cause is CancellationException)
            tree.proveAndClose(identities)
            Chapter7TestSupport.evidence("pty-cancel", JSONObject().put("identities", JSONArray(identities.map { it.json() })))
        }
    }

    @Test fun shortTestOwnedDeadlineCancelsRealPtyWithoutClaimingProduction120sPassed() = Chapter7TestSupport.inFiles { files ->
        PtyTree(files).use { tree ->
            tree.start(Mode.HOLD)
            val identities = tree.capture()
            val timer = Executors.newSingleThreadScheduledExecutor()
            val began = System.nanoTime()
            try {
                timer.schedule({ tree.cancel() }, 250, TimeUnit.MILLISECONDS)
                val failure = assertThrows(ExecutionException::class.java) { tree.rawExit() }
                assertTrue(failure.cause is CancellationException)
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                assertTrue("test deadline did not fire", elapsed in 150..2500)
                tree.proveAndClose(identities)
                Chapter7TestSupport.evidence("pty-test-owned-timeout", JSONObject()
                    .put("elapsedMillis", elapsed).put("productionTimeoutClaimed", false)
                    .put("boundary", "test-owned scheduled cancel; production fixed 120s timer tested separately"))
            } finally { timer.shutdownNow() }
        }
    }

    @Test fun outputBeyond64KiBIsDrainedButCallbackSnapshotStaysUtf8Bounded() = Chapter7TestSupport.inFiles { files ->
        PtyTree(files).use { tree ->
            tree.start(Mode.OUTPUT_LIMIT)
            val identities = tree.capture()
            Chapter7TestSupport.await("PTY startup bytes were not delivered to the lifetime codec", 1500) {
                tree.output().isNotEmpty()
            }
            val chargedBeforeClear = tree.takePublishedOutputBytesAndClear()
            assertTrue("startup/echo bytes must already charge the lifetime codec", chargedBeforeClear in 1 until PtyCodec.MAX_OUTPUT_BYTES)
            tree.openGate()
            val exit = tree.exit()
            assertEquals(PtyExit.Kind.EXITED, exit.kind)
            assertEquals(0, exit.code)
            assertTrue(tree.output().contains("PTY_STDOUT"))
            assertTrue(tree.output().contains("PTY_STDERR"))
            val output = tree.output().toByteArray(Charsets.UTF_8)
            assertEquals(PtyCodec.MAX_OUTPUT_BYTES, chargedBeforeClear + output.size)
            assertTrue(output.all { (it.toInt() and 0xff) < 0x80 }) // Exact ASCII boundary; no UTF-8 split/replacement inflation.
            tree.proveAndClose(identities)
            tree.evidence("output-limit", exit, identities, JSONObject().put("chargedBeforeClear", chargedBeforeClear)
                .put("publishedAfterClear", output.size).put("lifetimeBoundedBytes", chargedBeforeClear + output.size)
                .put("ptyStreams", "stdout-and-stderr-merged"))
        }
    }

    @Test fun directRootTracerSigkillReportsRealSignalAndReapsEveryCapturedTracee() = Chapter7TestSupport.inFiles { files ->
        PtyTree(files).use { tree ->
            tree.start(Mode.HOLD)
            val identities = tree.capture()
            val tracer = identities.single { it.role == "tracer" }
            assertEquals(Process.myPid(), tracer.ppid)
            assertTrue(Chapter7TestSupport.same(tracer))
            Os.kill(tracer.pid, OsConstants.SIGKILL)
            val exit = tree.exit()
            assertEquals(PtyExit.Kind.SIGNALED, exit.kind)
            assertEquals(OsConstants.SIGKILL, exit.code)
            tree.proveAndClose(identities)
            tree.evidence("root-tracer-sigkill", exit, identities)
        }
    }

    @Test fun repeatedRealPtyRunsReturnFileDescriptorsAndRunLeases() = Chapter7TestSupport.inFiles { files ->
        val before = Chapter7TestSupport.fdCount()
        repeat(3) { index ->
            PtyTree(files).use { tree ->
                tree.start(Mode.NORMAL)
                val identities = tree.capture()
                tree.openGate()
                assertEquals(0, tree.exit().code)
                tree.proveAndClose(identities)
                tree.installer.validateInstalled() // Interactive history must never mutate installed data-only rootfs.
                assertFalse(tree.runDirectory.exists())
                assertTrue(Chapter7TestSupport.payloadNames(File(files, "linux/runs")).isEmpty())
                Chapter7TestSupport.evidence("pty-repeat-$index", JSONObject().put("fdCount", Chapter7TestSupport.fdCount()))
            }
        }
        Chapter7TestSupport.await("PTY native descriptors did not return", 2500) {
            Chapter7TestSupport.fdCount() <= before + 2
        }
    }
}

internal enum class Mode { NORMAL, HOLD, OUTPUT_LIMIT }

/** Fixed trusted shell recipe; marker PIDs come from the actual tracees, never Java Process or host selection. */
internal class PtyTree(val files: File) : Closeable {
    val installer = Chapter7TestSupport.installer(files)
    private val installation = installer.prepare()
    val workspace = File(WorkspaceSession(files).use { it.prepare() }.directory)
    private val lease = LinuxDirectoryLease.allocate(File(files, "linux/runs"), LinuxDirectoryLease.Kind.RUN,
        LinuxRecoveryProbe::isUnusedByAppProcesses)
    val runDirectory: File = lease.directory
    private val tmp = File(runDirectory, "tmp").apply { RootfsTar.privateDirectory(this) }
    private val token = "pty-tree-${UUID.randomUUID().toString().substring(0, 8)}"
    private val terminal = PtySession()
    private val reader = Executors.newSingleThreadExecutor()
    private val output = StringBuilder()
    private var operation: PtySession.Operation? = null
    private var future: Future<PtyExit>? = null
    private var identities: List<Chapter7TestSupport.Identity> = emptyList()
    private var cleanupAttempted = false

    fun start(mode: Mode) {
        check(operation == null)
        val config = PtyTrustedConfig.create(files, installer.nativeDirectory, installation.root, workspace,
            runDirectory, Process.myUid(), Process.myPid())
        require(config.temporaryDirectory == tmp.canonicalFile)
        val owned = terminal.open(token, config) { chunk -> synchronized(output) { output.append(chunk) } }
        operation = owned
        terminal.resize(owned, 93, 29)
        future = reader.submit<PtyExit> { terminal.waitForExit(owned) }
        val suffix = when (mode) {
            Mode.NORMAL -> "exit 0"
            Mode.HOLD -> "exec /bin/busybox sleep 30"
            Mode.OUTPUT_LIMIT -> "printf 'PTY_STDOUT\\n'; printf 'PTY_STDERR\\n' >&2; i=0; " +
                "while [ \"\$i\" -lt 2400 ]; do printf '${"x".repeat(80)}\\n'; i=\$((i+1)); done; exit 0"
        }
        val script = ("/bin/busybox stty -echo; " +
            "/bin/busybox sh -c 'echo \"\$\$\" > /tmp/background.pid; exec /bin/busybox sleep 30' & " +
            "/bin/busybox setsid /bin/busybox sh -c 'echo \"\$\$\" > /tmp/detached.pid; exec /bin/busybox sleep 30' & " +
            "echo \"\$\$\" > /tmp/guest.pid; " +
            "/bin/busybox mkfifo /tmp/start; printf ready > /tmp/ready; " +
            "read action < /tmp/start || exit 126; $suffix\n")
        assertTrue(script.toByteArray(Charsets.UTF_8).size <= PtySession.MAX_INPUT_BYTES)
        assertEquals(script.toByteArray().size, terminal.write(owned, script.toByteArray()))
    }

    fun capture(): List<Chapter7TestSupport.Identity> {
        Chapter7TestSupport.await("PTY tree did not publish fixed PID markers", 3000) {
            File(tmp, "ready").isFile && listOf("guest", "background", "detached").all {
                File(tmp, "$it.pid").takeIf(File::isFile)?.readText()?.trim()?.toIntOrNull() != null
            }
        }
        val guests = listOf("guest", "background", "detached").map { role ->
            Chapter7TestSupport.identity(role, File(tmp, "$role.pid").readText().trim().toInt())
        }
        require(guests.all { it.uid == Process.myUid() && it.tracer > 0 })
        require(guests.single { it.role == "detached" }.let { it.sid == it.pid })
        val tracerPid = Chapter7TestSupport.identity("tracer-tid", guests.first().tracer).tgid
        val tracer = Chapter7TestSupport.identity("tracer", tracerPid)
        require(tracer.ppid == Process.myPid())
        require(tracer.cmdline.contains(installer.nativeDirectory.path + "/libproot.so"))
        val tasks = checkNotNull(File("/proc/$tracerPid/task").list()).map {
            Chapter7TestSupport.identity("tracer-tid", it.toInt())
        }
        require(guests.all { guest -> tasks.any { it.pid == guest.tracer } })
        identities = (listOf(tracer) + guests + tasks).distinctBy { it.pid }
        require(identities.size <= 32)
        return identities
    }

    fun openGate() {
        var descriptor: java.io.FileDescriptor? = null
        Chapter7TestSupport.await("PTY FIFO reader unavailable", 1500) {
            try {
                descriptor = Os.open(File(tmp, "start").path, OsConstants.O_WRONLY or OsConstants.O_NONBLOCK, 0)
                true
            } catch (error: ErrnoException) { if (error.errno == OsConstants.ENXIO) false else throw error }
        }
        val fd = checkNotNull(descriptor)
        try { assertEquals(3, Os.write(fd, "go\n".toByteArray(), 0, 3)) } finally { Os.close(fd) }
    }

    fun cancel() = terminal.cancel(token)
    fun rawExit(): PtyExit = checkNotNull(future).get(7, TimeUnit.SECONDS)
    fun exit(): PtyExit = rawExit()
    fun output(): String = synchronized(output) { output.toString() }
    fun takePublishedOutputBytesAndClear(): Int = synchronized(output) {
        output.toString().toByteArray(Charsets.UTF_8).size.also { output.setLength(0) }
    }

    fun proveAndClose(expected: List<Chapter7TestSupport.Identity>) {
        Chapter7TestSupport.assertGone(expected)
        terminal.close()
        lease.close()
        cleanupAttempted = true
        assertTrue("PTY RUN lease cleanup unproven", lease.cleanupProven)
        assertFalse(runDirectory.exists())
    }

    fun evidence(stage: String, exit: PtyExit, expected: List<Chapter7TestSupport.Identity>, extra: JSONObject = JSONObject()) {
        Chapter7TestSupport.evidence(stage, extra.put("token", token).put("childPid", exit.childPid)
            .put("kind", exit.kind.name).put("code", exit.code).put("rootReaped", exit.rootReaped)
            .put("outputBytes", output().toByteArray(Charsets.UTF_8).size)
            .put("runDirectory", runDirectory.path).put("identities", JSONArray(expected.map { it.json() })))
    }

    override fun close() {
        try { terminal.close() } finally {
            reader.shutdownNow()
            reader.awaitTermination(1, TimeUnit.SECONDS)
            if (!cleanupAttempted) {
                try { lease.close() } catch (_: Exception) { /* Preserve registered unknown state. */ }
            }
        }
    }
}
