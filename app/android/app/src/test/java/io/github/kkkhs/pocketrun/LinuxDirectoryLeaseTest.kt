package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class LinuxDirectoryLeaseTest {
    @Test fun liveProducerIsNotRecoveredAndForeignDirectoriesAreNeverDeleted() = RootfsTarTest.temporary { parent ->
        val foreign = File(parent, "another-run").apply { mkdir() }
        File(foreign, "keep").writeText("user")
        LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.STAGE).use { live ->
            File(live.directory, "partial").writeText("still writing")
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.STAGE)
            assertTrue(File(live.directory, "partial").isFile)
            assertTrue(foreign.isDirectory)
        }
        assertEquals(listOf("another-run"), remaining(parent))
    }

    @Test fun runRecoveryRequiresExplicitUnusedProofAndUnknownRetainsData() = RootfsTarTest.temporary { parent ->
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        File(run.directory, "tmp").mkdir()
        File(run.directory, "tmp/keep").writeText("guest may still use this")
        val directory = run.directory
        run.close()
        assertFalse(run.cleanupProven)
        assertTrue(directory.isDirectory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> throw java.io.IOException("proc unknown") })
        assertTrue(directory.isDirectory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        assertTrue(directory.isDirectory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { candidate, _ -> candidate == directory })
        assertFalse(directory.exists())
        assertTrue(remaining(parent).isEmpty())
    }

    @Test(timeout = 10000) fun realJvmHardDeathReleasesKernelLeaseAndAllowsStageRecovery() = RootfsTarTest.temporary { parent ->
        val process = startWorker(parent, "STAGE", "wait")
        try {
            val directory = File(process.inputStream.bufferedReader().readLine() ?: error(process.errorStream.bufferedReader().readText()))
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.STAGE)
            assertTrue(File(directory, "partial").isFile) // The other JVM still owns the lock.
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.STAGE)
            assertFalse(directory.exists())
            assertTrue(remaining(parent).isEmpty())
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }

    @Test(timeout = 10000) fun hardDeadRunOwnerAloneDoesNotAuthorizeDeletingGuestTmp() = RootfsTarTest.temporary { parent ->
        val process = startWorker(parent, "RUN", "halt")
        try {
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
            val directory = File(process.inputStream.bufferedReader().readText().trim())
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
            assertTrue(File(directory, "partial").isFile)
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
            assertFalse(directory.exists())
        } finally { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
    }

    @Test fun permissionOrIoUnknownIsNotAbsenceAndCannotClaimCleanup() = RootfsTarTest.temporary { parent ->
        val inspect: (File) -> java.nio.file.attribute.BasicFileAttributes? = { file ->
            if (file.name.startsWith("pocketrun-run-")) throw java.nio.file.AccessDeniedException(file.path)
            LinuxDirectoryLease.readAttributes(file)
        }
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true }, inspect = inspect)
        File(run.directory, "keep").writeText("must retain")
        val command = LinuxSession.Result("fixture", "fixed", "completed", "kept stdout", "", listOf(0),
            listOf(0, 0, 0, 0, 0), 1, 10469, "3.22.8", 10469, "/workspace", 3)
        val result = LinuxSession.cleanupResult(command, run)
        assertEquals("unproven", result.cleanupStatus)
        assertEquals("kept stdout", result.stdout)
        assertFalse(run.cleanupProven)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true }, inspect = inspect)
        assertEquals("must retain", File(run.directory, "keep").readText())
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        assertFalse(run.directory.exists())
    }

    @Test fun emptyCorruptAndForeignIdentityRecordsDoNotAuthorizeTreeDeletion() = RootfsTarTest.temporary { parent ->
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        File(run.directory, "keep").writeText("owned but ownership must be proven")
        run.close()
        val marker = File(parent, ".${run.directory.name}.lease")
        val original = marker.readText()
        fun changed(index: Int, value: String) = original.split('\n').toMutableList().also { it[index] = value }.joinToString("\n")
        val invalid = listOf("", "corrupt", changed(0, "foreign-schema"), changed(1, "another-directory"),
            changed(2, "stage"), changed(3, "not-a-generation"), changed(4, "4294967295"),
            changed(5, "0"), changed(6, "bad-ticks"), changed(7, "0".repeat(64)), changed(8, "foreign-owner"))
        invalid.forEach { record ->
            marker.writeText(record)
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
            assertTrue(File(run.directory, "keep").isFile)
            assertTrue(marker.isFile) // Torn/foreign registration metadata is retained, not called an orphan proof.
        }
        marker.writeText(original)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        assertFalse(run.directory.exists())
    }

    @Test fun corruptedLiveRegistrationCannotReportDoneWhenClosed() = RootfsTarTest.temporary { parent ->
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        File(parent, ".${run.directory.name}.lease").writeText("")
        run.close()
        assertFalse(run.cleanupProven)
        assertTrue(run.directory.isDirectory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        assertTrue(run.directory.isDirectory)
    }

    @Test fun onePassBoundsCandidateCountAndPassesRemainingBudgetAndGuard() = RootfsTarTest.temporary { parent ->
        val runs = (1..3).map { LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false }) }
        runs.forEach { it.close() }
        var scans = 0
        var guards = 0
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, budget ->
            scans++
            assertTrue(budget in 1..1500)
            Thread.sleep(50)
            false
        }, guard = { guards++ })
        assertEquals(1, scans)
        assertTrue(guards > 1)
        assertTrue(runs.all { it.directory.isDirectory })
        repeat(3) { LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true }) }
        assertTrue(runs.none { it.directory.exists() })
    }

    @Test(timeout = 10000) fun waitingForAnotherRecoveryHonorsOperationCancellation() = RootfsTarTest.temporary { parent ->
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        run.close()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit {
                LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ ->
                    entered.countDown(); release.await(5, TimeUnit.SECONDS); false
                })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            var guards = 0
            RootfsTarTest.expectFailure { LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.STAGE, guard = {
                guards++; throw java.util.concurrent.CancellationException("cancel while waiting")
            }) }
            assertEquals(1, guards)
            release.countDown()
            task.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); worker.shutdownNow(); worker.awaitTermination(5, TimeUnit.SECONDS) }
    }

    @Test fun fatalErrorDuringAllocationDoesNotLeakARegistrationOrKernelLock() = RootfsTarTest.temporary { parent ->
        val fatal = AssertionError("allocation guard")
        try {
            LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.STAGE, guard = {
                if (parent.list().orEmpty().any { it.endsWith(".lease") }) throw fatal
            })
            fail("must rethrow Error")
        } catch (error: AssertionError) { assertSame(fatal, error) }
        assertTrue(remaining(parent).isEmpty())
        LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.STAGE).use { assertTrue(it.directory.isDirectory) }
        assertTrue(remaining(parent).isEmpty())
    }

    @Test fun actualNoSuchDirectoryCanRemoveOnlyItsValidRegistration() = RootfsTarTest.temporary { parent ->
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        run.close()
        RootfsInstaller.removeOwned(run.directory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> error("missing directory needs no proc proof") })
        assertTrue(remaining(parent).isEmpty())
    }

    private fun remaining(parent: File) = parent.list().orEmpty().filter { it != ".pocketrun-recovery.lock" }
    private fun startWorker(parent: File, kind: String, ending: String): Process {
        val locations = listOf(LinuxLeaseCrashWorker::class.java, LinuxDirectoryLease::class.java, Unit::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).path }
        val classpath = (locations + System.getProperty("java.class.path").split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
        return ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
            "-cp", classpath, LinuxLeaseCrashWorker::class.java.name, parent.path, kind, ending).start()
    }
}

/** Host JVM evidence for FileLock, not an Android/guest process-lifecycle acceptance test. */
object LinuxLeaseCrashWorker {
    @JvmStatic fun main(args: Array<String>) {
        val lease = LinuxDirectoryLease.allocate(File(args[0]), LinuxDirectoryLease.Kind.valueOf(args[1]))
        File(lease.directory, "partial").writeText("hard death fixture")
        println(lease.directory.path)
        System.out.flush()
        if (args[2] == "wait") System.`in`.read()
        Runtime.getRuntime().halt(0) // Deliberately bypass finally/close/shutdown hooks.
    }
}
