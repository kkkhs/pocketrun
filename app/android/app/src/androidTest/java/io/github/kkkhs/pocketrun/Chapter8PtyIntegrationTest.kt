package io.github.kkkhs.pocketrun

import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Chapter8PtyIntegrationTest {
    @Test fun realPtyInputResizeControlExitAndOwnedCleanup() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val files = Files.createTempDirectory(context.filesDir.toPath(), "chapter8-pty-").toFile().canonicalFile
        val beforeFds = checkNotNull(File("/proc/self/fd").list()).size
        var passed = false
        var lease: LinuxDirectoryLease? = null
        var terminal: PtySession? = null
        val waiter = Executors.newSingleThreadExecutor()
        val output = StringBuilder()
        try {
            val runtime = LinuxRuntimeManifest.read(context.assets.open("linux/runtime.json"))
            val installer = RootfsInstaller(files, File(context.applicationInfo.nativeLibraryDir).canonicalFile,
                runtime, context.assets::open)
            val info = LinuxSession(files, installer, Process.myUid(), Process.myPid(),
                canRemoveRun = LinuxRecoveryProbe::isUnusedByAppProcesses).use { linux ->
                linux.prepare(linux.reservePrepare("pty-real-prepare"))
            }
            val runs = File(files, "linux/runs")
            val ownedLease = LinuxDirectoryLease.allocate(runs, LinuxDirectoryLease.Kind.RUN,
                LinuxRecoveryProbe::isUnusedByAppProcesses)
            lease = ownedLease
            val tmp = File(ownedLease.directory, "tmp").apply { RootfsTar.privateDirectory(this) }
            val config = PtyTrustedConfig.create(files, installer.nativeDirectory,
                File(info.rootfsDirectory), File(info.workspaceDirectory), ownedLease.directory,
                Process.myUid(), Process.myPid())
            assertEquals(tmp.canonicalFile, config.temporaryDirectory)
            val pty = PtySession()
            terminal = pty
            val operation = pty.open("pty-real", config) { chunk -> synchronized(output) { output.append(chunk) } }
            pty.resize(operation, 80, 24)
            val future = waiter.submit<PtyExit> { pty.waitForExit(operation) }
            pty.resize(operation, 97, 31) // Runtime resize while the reader and native child are active.
            val command = "/bin/busybox stty -echo; " +
                "trap 'printf \"PTY_CONTROL_INT\\n\"; exit 23' INT; " +
                "/bin/busybox stty size; printf 'PTY_INPUT_OK\\n'; " +
                "while :; do /bin/busybox sleep 1; done\n"
            assertEquals(command.toByteArray().size, pty.write(operation, command.toByteArray()))
            await("PTY did not execute input or apply 31x97 size") {
                synchronized(output) { output.contains("PTY_INPUT_OK") && output.contains("31 97") }
            }
            synchronized(output) { output.setLength(0) } // Discard the initially echoed command before control proof.
            pty.sendControl(operation, PtySession.Control.INTERRUPT)
            val exit = future.get(5, TimeUnit.SECONDS)
            val captured = synchronized(output) { output.toString() }
            assertTrue(captured, captured.contains("PTY_CONTROL_INT"))
            assertEquals(PtyExit.Kind.EXITED, exit.kind)
            assertEquals(23, exit.code)
            assertTrue(exit.rootReaped)
            assertTrue(exit.childPid > 1)
            pty.close()
            ownedLease.close()
            assertTrue("RUN cleanup remains unproven", ownedLease.cleanupProven)
            assertFalse(ownedLease.directory.exists())
            await("PTY leaked App file descriptors") {
                checkNotNull(File("/proc/self/fd").list()).size <= beforeFds
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("chapter8_pty", JSONObject()
                    .put("appUid", Process.myUid()).put("childPid", exit.childPid)
                    .put("exitKind", exit.kind.name).put("exitCode", exit.code)
                    .put("columns", 97).put("rows", 31).put("rootReaped", exit.rootReaped)
                    .put("cleanupStatus", "done").put("output", captured.take(4096)).toString())
            })
            passed = true
        } finally {
            if (!passed) {
                try { terminal?.close() } catch (_: Exception) {}
                try { lease?.close() } catch (_: Exception) {}
            }
            waiter.shutdownNow()
            waiter.awaitTermination(1, TimeUnit.SECONDS)
            if (passed) assertTrue(files.deleteRecursively())
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(message, condition())
    }
}
