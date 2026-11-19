package io.github.kkkhs.pocketrun

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FilterInputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** No mock runtime/process: production installer/session, real APK ELF, BusyBox, PRoot, ptrace and setsid. */
@RunWith(AndroidJUnit4::class)
class Chapter7LifecycleIntegrationTest {
    @Test fun archiveAndElfHashFailuresDoNotPublishAndPreserveUnownedStages() = Chapter7TestSupport.inFiles { files ->
        val runtime = Chapter7TestSupport.runtime()
        val foreign = File(files, "linux/stage/not-this-attempt").apply { mkdirs() }
        foreign.resolve("keep").writeText("owned by a different attempt")
        val wrongData = Chapter7TestSupport.installer(files, runtime.copy(dataSha256 = "0".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { wrongData.prepare() }
        assertEquals(listOf("not-this-attempt"), Chapter7TestSupport.payloadNames(foreign.parentFile!!))
        assertTrue(File(files, "linux").listFiles()!!.none { it.name.startsWith("rootfs-") })
        val badArtifacts = runtime.artifacts.mapIndexed { index, value -> if (index == 0) value.copy(sha256 = "0".repeat(64)) else value }
        assertThrows(IllegalArgumentException::class.java) { Chapter7TestSupport.installer(files, runtime.copy(artifacts = badArtifacts)).prepare() }
        val valid = Chapter7TestSupport.installer(files)
        assertTrue(valid.prepare().created)
        assertFalse(valid.prepare().created)
        assertEquals("owned by a different attempt", foreign.resolve("keep").readText())
        runtime.artifacts.forEach { artifact ->
            val installed = File(valid.nativeDirectory, artifact.name)
            assertFalse(installed.canWrite())
            assertEquals(artifact.sha256, RootfsInstaller.hash(installed))
        }
        Chapter7TestSupport.evidence("real-hash-failure-recovery", JSONObject().put("generation", runtime.generation))
    }

    @Test fun cancelledArchiveCopyAndFailedCommitCleanOnlyCurrentStage() = Chapter7TestSupport.inFiles { files ->
        val installer = Chapter7TestSupport.installer(files)
        assertThrows(CancellationException::class.java) {
            installer.prepare(guard = {
                if (File(files, "linux/stage").listFiles().orEmpty().any { it.resolve("data.tar.gz").length() > 0 })
                    throw CancellationException("real copied payload cancellation")
            })
        }
        assertTrue(Chapter7TestSupport.payloadNames(File(files, "linux/stage")).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            installer.prepare(commit = { throw IllegalStateException("test commit boundary") })
        }
        assertTrue(Chapter7TestSupport.payloadNames(File(files, "linux/stage")).isEmpty())
        assertTrue(File(files, "linux").listFiles()!!.none { it.name.startsWith("rootfs-") })
        assertTrue(installer.prepare().created)
    }

    @Test fun productionEpochRepeatPrepareCancelAndClosedStatePreserveUserData() = Chapter7TestSupport.inFiles { files ->
        val session = Chapter7TestSupport.session(files)
        session.use {
            val old = session.reservePrepare("epoch-old")
            val info = session.prepare(old)
            val sample = File(info.workspaceDirectory, "items.csv").apply { writeText("retained\ndata\n") }
            val inode = Os.stat(sample.path).st_ino
            val fresh = session.reservePrepare("epoch-new")
            assertFalse(session.cancel(old.token))
            assertThrows(IllegalStateException::class.java) { session.prepare(old) }
            val second = session.prepare(fresh)
            assertEquals("epoch-new", second.token)
            assertEquals(info.installationGeneration, second.installationGeneration)
            assertFalse(second.installedNow)
            assertFalse(second.sampleCreated)
            assertEquals(inode, Os.stat(sample.path).st_ino)
            assertEquals("retained\ndata\n", sample.readText())
            val beforeWorker = session.reserve("cancel-before-worker")
            assertTrue(session.cancel(beforeWorker.token))
            val cancelled = session.runDemo(beforeWorker)
            assertEquals("cancelled", cancelled.status)
            assertEquals("done", cancelled.cleanupStatus)
            assertTrue(cancelled.exitCodes.isEmpty())
            val completed = session.runDemo(session.reserve("new-after-cancel"))
            assertEquals("completed", completed.status)
            assertEquals("done", completed.cleanupStatus)
            assertEquals(Process.myUid(), completed.guestUid)
            assertEquals(2, completed.lineCount)
            assertEquals("new-after-cancel", completed.token)
            assertEquals(listOf(0, 0, 0, 0, 0), completed.stageExitCodes)
            assertEquals(0x1c0, Os.stat(info.rootfsDirectory).st_mode and 0x1ff)
            session.close()
            assertThrows(IllegalStateException::class.java) { session.reserve("after-close") }
            assertThrows(IllegalStateException::class.java) { session.reservePrepare("after-close") }
        }
    }

    @Test fun reserveDeadlineExpiresBeforePreparationAndDoesNotPublish() = Chapter7TestSupport.inFiles { files ->
        Chapter7TestSupport.session(files).use { session ->
            val operation = session.reservePrepare("expired-before-work")
            Thread.sleep(CommandPipeline.TIMEOUT_MS + 50)
            assertThrows(java.util.concurrent.TimeoutException::class.java) { session.prepare(operation) }
            assertFalse(File(files, "linux").exists())
            assertFalse(File(files, "workspaces").exists())
            assertTrue(session.prepare(session.reservePrepare("retry-after-expired")).installedNow)
        }
    }

    @Test fun realPrepareInFlightCancelAndClosePreventRootfsPublication() {
        for (closing in listOf(false, true)) Chapter7TestSupport.inFiles { files ->
            val entered = CountDownLatch(1)
            val resume = CountDownLatch(1)
            val firstRead = AtomicBoolean(true)
            val runtime = Chapter7TestSupport.runtime()
            val installer = RootfsInstaller(files, File(Chapter7TestSupport.context.applicationInfo.nativeLibraryDir).canonicalFile, runtime, { name ->
                object : FilterInputStream(Chapter7TestSupport.context.assets.open(name)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (firstRead.compareAndSet(true, false)) {
                            entered.countDown()
                            check(resume.await(3, TimeUnit.SECONDS))
                        }
                        return super.read(buffer, offset, length)
                    }
                }
            })
            val worker = Executors.newSingleThreadExecutor()
            val session = Chapter7TestSupport.session(files, installer)
            try {
                val operation = session.reservePrepare("in-flight-prepare")
                val future = worker.submit<LinuxSession.Info> { session.prepare(operation) }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                if (closing) session.close() else assertTrue(session.cancel(operation.token))
                resume.countDown()
                val failure = assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }
                assertTrue(failure.cause is CancellationException)
                assertTrue(Chapter7TestSupport.payloadNames(File(files, "linux/stage")).isEmpty())
                assertTrue(File(files, "linux").listFiles().orEmpty().none { it.name.startsWith("rootfs-") })
                if (closing) assertThrows(IllegalStateException::class.java) { session.reservePrepare("closed") }
                else assertTrue(session.prepare(session.reservePrepare("retry-prepare")).installedNow)
            } finally { resume.countDown(); session.close(); worker.shutdownNow() }
        }
    }

    @Test fun defaultAndFallbackCancelCloseAndTimeoutKillAllTracedSetsidJobs() {
        for (fallback in listOf(false, true)) for (mode in listOf("cancel", "close", "timeout"))
            Chapter7TestSupport.inFiles { files ->
                Chapter7TestSupport.Tree(files, fallback).use { tree ->
                    tree.start()
                    val identities = tree.capture()
                    if (mode == "cancel") assertTrue(tree.pipe.cancel())
                    if (mode == "close") tree.pipe.close()
                    val value = tree.result()
                    val aliveAtReturn = tree.run.processes.any { it.isAlive }
                    Chapter7TestSupport.result("$mode-fallback-$fallback", value)
                    if (value.exitCodes.any { it == -1 } || aliveAtReturn)
                        Chapter7TestSupport.procEvidence("$mode-java-alive-fallback-$fallback", identities)
                    assertEquals(if (mode == "timeout") "timeout" else "cancelled", value.status)
                    assertFalse("Runner returned before reaping the root process", aliveAtReturn)
                    assertTrue("Runner returned an unreaped exit code", value.exitCodes.none { it == -1 })
                    assertTrue(tree.run.processes.none { it.isAlive })
                    assertTrue(tree.pipe.runningProcesses().isEmpty())
                    Chapter7TestSupport.assertCleanup(value, tree.run)
                    Chapter7TestSupport.assertGone(identities)
                    if (mode == "close") assertThrows(IllegalStateException::class.java) { tree.pipe.reserve() }
                    else tree.rerun()
                }
            }
    }

    @Test fun defaultAndFallbackOutputLimitsStopAllJobsAndAllowRealRerun() {
        for (fallback in listOf(false, true)) for (stream in listOf("stdout", "stderr"))
            Chapter7TestSupport.inFiles { files ->
                Chapter7TestSupport.Tree(files, fallback).use { tree ->
                    tree.start(output = stream)
                    val identities = tree.capture()
                    tree.openGate()
                    val value = tree.result()
                    val aliveAtReturn = tree.run.processes.any { it.isAlive }
                    Chapter7TestSupport.result("$stream-limit-fallback-$fallback", value)
                    if (value.exitCodes.any { it == -1 } || aliveAtReturn)
                        Chapter7TestSupport.procEvidence("$stream-limit-java-alive-fallback-$fallback", identities)
                    assertEquals("output_limit", value.status)
                    assertFalse("Output limit returned before root reaping", aliveAtReturn)
                    assertTrue("Output limit returned an unreaped exit code", value.exitCodes.none { it == -1 })
                    assertTrue(value.stdout.toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                    assertTrue(value.stderr.toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                    Chapter7TestSupport.assertCleanup(value, tree.run)
                    Chapter7TestSupport.assertGone(identities)
                    tree.rerun()
                }
            }
    }

    @Test fun initialGuestExitZeroIsNotOverwrittenByBackgroundCleanupSignals() {
        for (fallback in listOf(false, true)) Chapter7TestSupport.inFiles { files ->
            Chapter7TestSupport.Tree(files, fallback).use { tree ->
                tree.start(finishNormally = true)
                val identities = tree.capture()
                tree.openGate()
                val value = tree.result()
                Chapter7TestSupport.result("initial-guest-exit-zero-fallback-$fallback", value)
                if (value.exitCodes.any { it == -1 } || tree.run.processes.any { it.isAlive })
                    Chapter7TestSupport.procEvidence("exit-zero-java-alive-fallback-$fallback", identities)
                // --kill-on-exit kills both sleepers, but those cleanup signals must not replace main exit 0.
                assertEquals("completed", value.status)
                assertEquals(listOf(0), value.exitCodes)
                Chapter7TestSupport.assertCleanup(value, tree.run)
                Chapter7TestSupport.assertGone(identities)
                tree.rerun()
            }
        }
    }

    @Test fun guestAndTracerSigkillReapEveryDefaultAndFallbackTracee() {
        for (fallback in listOf(false, true)) for (role in listOf("guest", "tracer"))
            Chapter7TestSupport.inFiles { files ->
                Chapter7TestSupport.Tree(files, fallback).use { tree ->
                    tree.start()
                    val identities = tree.capture()
                    val target = identities.single { it.role == role }
                    require(Chapter7TestSupport.same(target))
                    Os.kill(target.pid, OsConstants.SIGKILL)
                    val value = tree.result()
                    val aliveAtReturn = tree.run.processes.any { it.isAlive }
                    Chapter7TestSupport.result("sigkill-$role-fallback-$fallback", value)
                    if (value.status != "failed" || value.exitCodes.any { it == -1 } || aliveAtReturn)
                        Chapter7TestSupport.procEvidence("sigkill-$role-unexpected-fallback-$fallback", identities)
                    assertEquals("failed", value.status)
                    assertFalse("SIGKILL result returned before root reaping", aliveAtReturn)
                    assertEquals("SIGKILL must report a real signal exit, never an unreaped -1 sentinel", listOf(137), value.exitCodes)
                    Chapter7TestSupport.assertCleanup(value, tree.run)
                    Chapter7TestSupport.assertGone(identities)
                    tree.rerun()
                }
            }
    }

    @Test fun realProductionRepeatedRunsRecoverFileDescriptorsAndIoThreads() = Chapter7TestSupport.inFiles { files ->
        Chapter7TestSupport.session(files).use { session ->
            session.prepare(session.reservePrepare("fd-prepare"))
            val warm = session.runDemo(session.reserve("fd-warm"))
            assertEquals("completed", warm.status)
            assertEquals("done", warm.cleanupStatus)
            Chapter7TestSupport.await("Warm IO did not settle") { Chapter7TestSupport.ioThreads() == 0 }
            val before = Chapter7TestSupport.fdCount()
            repeat(4) { index ->
                val value = session.runDemo(session.reserve("fd-$index"))
                Chapter7TestSupport.evidence("production-result-$index", JSONObject()
                    .put("status", value.status).put("cleanupStatus", value.cleanupStatus).put("token", value.token))
                assertEquals("completed", value.status)
                assertEquals("done", value.cleanupStatus)
                assertTrue(Chapter7TestSupport.payloadNames(File(files, "linux/runs")).isEmpty())
            }
            Chapter7TestSupport.await("Production FDs did not return") { Chapter7TestSupport.fdCount() <= before + 2 }
            assertEquals(0, Chapter7TestSupport.ioThreads())
            Chapter7TestSupport.evidence("production-fds", JSONObject().put("before", before)
                .put("after", Chapter7TestSupport.fdCount()).put("ioThreads", Chapter7TestSupport.ioThreads()))
        }
    }

    @Test fun realInstallerRejectsSymlinkEscapeAndDoesNotReplaceCorruptedRootfs() = Chapter7TestSupport.inFiles { files ->
        val outside = File(files, "outside").apply { mkdir() }
        Files.createSymbolicLink(File(files, "linux").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { Chapter7TestSupport.installer(files).prepare() }
        assertTrue(outside.list().orEmpty().isEmpty())
        File(files, "linux").delete()
        val installer = Chapter7TestSupport.installer(files)
        val installation = installer.prepare()
        val version = File(installation.root, "etc/alpine-release").apply { writeText("user corruption retained\n") }
        val inode = Os.stat(version.path).st_ino
        assertThrows(IllegalArgumentException::class.java) { installer.prepare() }
        assertEquals("user corruption retained\n", version.readText())
        assertEquals(inode, Os.stat(version.path).st_ino)
        assertTrue(Chapter7TestSupport.payloadNames(File(files, "linux/stage")).isEmpty())
    }
}
