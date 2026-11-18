package io.github.kkkhs.pocketrun

import android.os.Bundle
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android processes under the ordinary App UID; no mock process or shell-UID execution. */
@RunWith(AndroidJUnit4::class)
class Chapter6WorkspaceIntegrationTest {
    @Test fun realAndroidToyboxUsesAppUidAndPrivateWorkingDirectory() = inPrivateRoot { root ->
        CommandPipeline().use { pipe ->
            val uid = pipe.execute(pipe.reserve(), root, listOf(toybox("id", "-u")))
            assertEquals("completed", uid.status)
            assertEquals(Process.myUid(), uid.stdout.trim().toInt())
            assertTrue(Process.myUid() != 0 && Process.myUid() != 2000)
        }
        WorkspaceSession(root).use { session ->
            val info = session.prepare()
            assertTrue(info.directory.startsWith(contextFiles().canonicalPath + File.separator))
            assertPrivate(File(info.directory), 0x1c0) // 0700
            assertPrivate(File(info.sampleFile), 0x180) // 0600
            val result = session.runDemo(session.reserve())
            assertEquals("completed", result.status)
            assertEquals("3", result.stdout.trim())
            assertEquals(listOf(0, 0), result.exitCodes)
            assertEquals("", result.stderr)
            session.close()
            assertTrue(File(info.sampleFile).isFile)
        }
    }

    @Test fun stdoutStderrAndIntermediateTransferHaveIndependentRealLimits() = inPrivateRoot { root ->
        root.resolve("large").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES + 8192) { 'x'.code.toByte() })
        root.resolve("exact").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES) { 'y'.code.toByte() })
        CommandPipeline().use { pipe ->
            val commands = listOf(
                listOf(toybox("cat", "large")),
                listOf(listOf("/system/bin/sh", "-c", "exec /system/bin/toybox cat large >&2")),
                listOf(toybox("cat", "large"), toybox("wc", "-c")),
            )
            for ((index, stages) in commands.withIndex()) {
                val run = pipe.reserve()
                val result = pipe.execute(run, root, stages)
                assertEquals("stream $index: $result", "output_limit", result.status)
                assertTrue(result.stdout.toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                assertTrue(result.stderr.toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                if (index == 0) assertEquals(CommandPipeline.MAX_OUTPUT_BYTES, result.stdout.length)
                if (index == 1) assertEquals(CommandPipeline.MAX_OUTPUT_BYTES, result.stderr.length)
                assertReclaimed(pipe, run)
                assertRerun(pipe, root)
            }
            val exact = pipe.execute(pipe.reserve(), root, listOf(toybox("cat", "exact"), toybox("wc", "-c")))
            assertEquals("completed", exact.status)
            assertEquals(CommandPipeline.MAX_OUTPUT_BYTES.toString(), exact.stdout.trim())
            assertEquals(listOf(0, 0), exact.exitCodes)
        }
    }

    @Test fun exactStdoutStderrAndMultibyteTruncationRespectReturnedUtf8Bound() = inPrivateRoot { root ->
        root.resolve("exact").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES) { 'a'.code.toByte() })
        root.resolve("unicode").writeText("a".repeat(CommandPipeline.MAX_OUTPUT_BYTES - 1) + "汉")
        root.resolve("invalid").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES) { 0xff.toByte() })
        CommandPipeline().use { pipe ->
            for (stderr in listOf(false, true)) {
                fun command(file: String) = if (stderr)
                    listOf("/system/bin/sh", "-c", "exec /system/bin/toybox cat $file >&2")
                else toybox("cat", file)
                val exact = pipe.execute(pipe.reserve(), root, listOf(command("exact")))
                assertEquals("completed", exact.status)
                assertEquals(CommandPipeline.MAX_OUTPUT_BYTES,
                    (if (stderr) exact.stderr else exact.stdout).toByteArray(Charsets.UTF_8).size)
                val run = pipe.reserve()
                val truncated = pipe.execute(run, root, listOf(command("unicode")))
                assertEquals("output_limit", truncated.status)
                val returned = if (stderr) truncated.stderr else truncated.stdout
                assertTrue("returned UTF-8 exceeds 64 KiB after decoding: ${returned.toByteArray(Charsets.UTF_8).size}",
                    returned.toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                assertTrue(returned.startsWith("a".repeat(CommandPipeline.MAX_OUTPUT_BYTES - 4)))
                assertReclaimed(pipe, run)
                assertRerun(pipe, root)
                val invalid = pipe.execute(pipe.reserve(), root, listOf(command("invalid")))
                val returnedBytes = (if (stderr) invalid.stderr else invalid.stdout).toByteArray(Charsets.UTF_8).size
                evidence("binary_${if (stderr) "stderr" else "stdout"} status=${invalid.status} returned_utf8_bytes=$returnedBytes")
                assertEquals("output_limit", invalid.status)
                assertTrue("invalid UTF-8 replacement expansion exceeded limit",
                    returnedBytes <= CommandPipeline.MAX_OUTPUT_BYTES)
                assertRerun(pipe, root)
            }
        }
    }

    @Test fun realTimeoutAndExpiredValidationDoNotLeaveChildrenOrSpawnSideEffects() = inPrivateRoot { root ->
        CommandPipeline().use { pipe ->
            val run = pipe.reserve()
            val result = pipe.execute(run, root, listOf(sleepWithPid()), 300)
            assertEquals("timeout", result.status)
            assertTrue("timeout was ${result.durationMillis} ms", result.durationMillis < 2500)
            // A busy device can time out between spawn and the PID-marker exec. That is a valid timeout.
            val marker = root.resolve("child.pid")
            val pid = if (marker.isFile) marker.readText().trim().toIntOrNull() else null
            assertReclaimed(pipe, run, pid)
            assertRerun(pipe, root)
            val expired = pipe.reserve()
            val notStarted = pipe.execute(expired, root, listOf(toybox("touch", "unexpected")), 25) {
                Thread.sleep(100)
            }
            assertEquals("timeout", notStarted.status)
            assertTrue(expired.processes.isEmpty())
            assertFalse(root.resolve("unexpected").exists())
            assertRerun(pipe, root)
        }
    }

    @Test fun cancellationBeforeExecutionNeverStartsAChildAndCanRerun() = inPrivateRoot { root ->
        CommandPipeline().use { pipe ->
            val run = pipe.reserve()
            assertTrue(pipe.cancel())
            val result = pipe.execute(run, root, listOf(toybox("touch", "unexpected")))
            assertEquals("cancelled", result.status)
            assertTrue(run.processes.isEmpty())
            assertFalse(root.resolve("unexpected").exists())
            assertFalse(pipe.cancel())
            assertRerun(pipe, root)
        }
    }

    @Test fun runningCancelAndCloseReapActualPidAndRejectFurtherUseAfterClose() = inPrivateRoot { root ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            for (closing in listOf(false, true)) {
                root.resolve("child.pid").delete()
                CommandPipeline().use { pipe ->
                    val run = pipe.reserve()
                    val result = worker.submit<CommandPipeline.Result> {
                        pipe.execute(run, root, listOf(sleepWithPid()))
                    }
                    val pid = waitForPid(root)
                    assertTrue(pid != Process.myPid())
                    if (closing) pipe.close() else assertTrue(pipe.cancel())
                    assertEquals("cancelled", result.get(3, TimeUnit.SECONDS).status)
                    assertReclaimed(pipe, run, pid)
                    if (closing) assertThrows(IllegalStateException::class.java) { pipe.reserve() }
                    else assertRerun(pipe, root)
                }
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun actualChildSigkillReturnsFailedAndAllowsRerun() = inPrivateRoot { root ->
        val worker = Executors.newSingleThreadExecutor()
        try {
            CommandPipeline().use { pipe ->
                val run = pipe.reserve()
                val future = worker.submit<CommandPipeline.Result> {
                    pipe.execute(run, root, listOf(sleepWithPid()))
                }
                val pid = waitForPid(root)
                Os.kill(pid, OsConstants.SIGKILL)
                val result = future.get(3, TimeUnit.SECONDS)
                assertEquals("failed", result.status)
                assertEquals(1, result.exitCodes.size)
                assertTrue(result.exitCodes.single() != 0)
                assertReclaimed(pipe, run, pid)
                assertRerun(pipe, root)
            }
        } finally { worker.shutdownNow() }
    }

    @Test fun missingExecutableAndFailingFirstStageAreNotHiddenByRerun() = inPrivateRoot { root ->
        CommandPipeline().use { pipe ->
            val missing = pipe.reserve()
            val noExec = pipe.execute(missing, root, listOf(listOf(root.resolve("not-executable").path)))
            assertEquals("failed", noExec.status)
            assertTrue(missing.processes.isEmpty())
            assertTrue(noExec.stderr.isNotBlank())
            assertRerun(pipe, root)
            val run = pipe.reserve()
            val failure = pipe.execute(run, root, listOf(toybox("cat", "missing.csv"), toybox("wc", "-l")))
            assertEquals("failed", failure.status)
            assertEquals(2, failure.exitCodes.size)
            assertTrue(failure.exitCodes.first() != 0)
            assertTrue(failure.stderr.isNotBlank())
            assertReclaimed(pipe, run)
            assertRerun(pipe, root)
        }
    }

    @Test fun repeatedRealRunsRecoverFileDescriptorsAndIoThreads() = inPrivateRoot { root ->
        CommandPipeline().use { pipe ->
            assertRerun(pipe, root) // Warm Android's process reaper and lazy resources first.
            await("warm IO threads") { ioThreads() == 0 }
            val before = fdCount()
            repeat(12) { index ->
                val run = pipe.reserve()
                val commands = if (index % 3 == 0) listOf(toybox("sleep", "30")) else listOf(toybox("id", "-u"))
                val result = pipe.execute(run, root, commands, if (index % 3 == 0) 50 else 5000)
                assertEquals(if (index % 3 == 0) "timeout" else "completed", result.status)
                assertReclaimed(pipe, run)
            }
            await("FDs before=$before now=${fdCount()}") { fdCount() <= before + 2 }
            evidence("fd_before=$before fd_after=${fdCount()} io_threads=${ioThreads()}")
            assertEquals(0, ioThreads())
        }
    }

    @Test fun realNoReplacePublicationPreservesFileCreatedAfterStagingFsync() = inPrivateRoot { root ->
        val sample = root.resolve("workspaces/demo/items.csv")
        var userInode = -1L
        WorkspaceSession(root, beforeSamplePublish = {
            assertFalse(sample.exists())
            sample.writeText("concurrent,user-data\n")
            userInode = Os.stat(sample.path).st_ino
        }).use { session ->
            val info = session.prepare()
            assertFalse(info.sampleCreated)
            assertEquals("concurrent,user-data\n", sample.readText())
            assertEquals(userInode, Os.stat(sample.path).st_ino)
            assertTrue(checkNotNull(sample.parentFile!!.listFiles()).none { it.name.startsWith(".pocketrun-sample-") })
        }
    }

    @Test fun realPrivateFilesystemPreservesExistingDataAndRejectsEscapes() = inPrivateRoot { root ->
        WorkspaceSession(root).use { session ->
            val first = session.prepare()
            val sample = File(first.sampleFile)
            val saved = "user-owned data\n".toByteArray()
            sample.writeBytes(saved)
            val inode = Os.stat(sample.path).st_ino
            assertFalse(session.prepare().sampleCreated)
            assertTrue(saved.contentEquals(sample.readBytes()))
            assertEquals(inode, Os.stat(sample.path).st_ino)
            session.close()
            assertTrue(saved.contentEquals(sample.readBytes()))
            assertPrivate(sample, 0x180)
        }
        val outside = root.resolve("outside").apply { mkdir() }
        root.resolve("workspaces").deleteRecursively()
        Files.createSymbolicLink(root.resolve("workspaces").toPath(), outside.toPath())
        WorkspaceSession(root).use { session ->
            assertThrows(IllegalArgumentException::class.java) { session.prepare() }
        }
        assertFalse(outside.resolve("demo").exists())
        root.resolve("workspaces").delete()
        WorkspaceSession(root).use { session ->
            val info = session.prepare()
            File(info.sampleFile).delete()
            val secret = outside.resolve("secret").apply { writeText("untouched") }
            Files.createSymbolicLink(File(info.sampleFile).toPath(), secret.toPath())
            assertThrows(IllegalArgumentException::class.java) { session.reserve() }
            assertEquals("untouched", secret.readText())
        }
    }

    private fun evidence(message: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2,
            Bundle().apply { putString("chapter6_evidence", message) })
    }

    private fun inPrivateRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory(contextFiles().toPath(), "chapter6-real-").toFile()
        try { test(root) } finally { assertTrue(root.deleteRecursively()) }
    }

    private fun assertPrivate(file: File, mode: Int) {
        val stat = Os.stat(file.path)
        assertEquals(Process.myUid(), stat.st_uid)
        assertEquals(mode, stat.st_mode and 0x1ff)
    }

    private fun assertRerun(pipe: CommandPipeline, root: File) {
        val run = pipe.reserve()
        val next = pipe.execute(run, root, listOf(toybox("id", "-u")))
        assertEquals("completed", next.status)
        assertEquals(Process.myUid().toString(), next.stdout.trim())
        assertReclaimed(pipe, run)
    }

    private fun assertReclaimed(pipe: CommandPipeline, run: CommandPipeline.Run, pid: Int? = null) {
        assertTrue(run.processes.none { it.isAlive })
        assertTrue(pipe.runningProcesses().isEmpty())
        if (pid != null) await("PID $pid was not reaped") { !File("/proc/$pid").exists() }
        await("PocketRun-PipeIO threads still live") { ioThreads() == 0 }
    }

    companion object {
        internal fun contextFiles(): File = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        internal fun toybox(vararg args: String) = listOf("/system/bin/toybox") + args
        // sh is solely a test PID marker/redirection fixture, not a public command API.
        internal fun sleepWithPid() = listOf("/system/bin/sh", "-c",
            "echo \$\$ > child.pid; exec /system/bin/toybox sleep 30")
        internal fun waitForPid(root: File): Int {
            val marker = root.resolve("child.pid")
            await("child did not publish its PID") { marker.isFile && marker.readText().trim().toIntOrNull() != null }
            val pid = marker.readText().trim().toInt()
            await("PID $pid did not exec toybox sleep") {
                runCatching { File("/proc/$pid/cmdline").readText().contains("toybox\u0000sleep") }.getOrDefault(false)
            }
            return pid
        }
        internal fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(message, condition())
        }
        private fun fdCount() = checkNotNull(File("/proc/self/fd").list()).size
        private fun ioThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "PocketRun-PipeIO" }
    }
}
