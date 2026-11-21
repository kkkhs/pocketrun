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

    @Test fun heldProducerIsSkippedBeforeMarkerInspectionOrOpeningDuringRecoveryAndAllocation() = RootfsTarTest.temporary { parent ->
        val a = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        val marker = File(parent, ".${a.directory.name}.lease")
        val original = checkNotNull(LinuxDirectoryLease.readAttributes(marker))
        var inspectedA = 0
        var probes = 0
        val inspect: (File) -> java.nio.file.attribute.BasicFileAttributes? = { file ->
            if (file == marker) inspectedA++
            LinuxDirectoryLease.readAttributes(file)
        }
        try {
            LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> probes++; true }, inspect = inspect)
            assertFalse(LinuxDirectoryLease.recoverOwned(a.directory, LinuxDirectoryLease.Kind.RUN,
                unused = { _, _ -> probes++; true }, inspect = inspect))
            val b = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN,
                unused = { _, _ -> probes++; true }, inspect = inspect)
            try {
                assertEquals(0, inspectedA)
                assertEquals(0, probes)
                val after = checkNotNull(LinuxDirectoryLease.readAttributes(marker))
                assertEquals(original.size(), after.size())
                assertEquals(original.lastModifiedTime(), after.lastModifiedTime())
                assertEquals(original.fileKey(), after.fileKey())
                assertTrue(a.directory.isDirectory)
                assertFalse(a.cleanupProven)
                assertEquals("not_closed", a.cleanupFailureReason)
            } finally { b.close() }
            assertTrue(b.cleanupProven)
            assertNull(b.cleanupFailureReason)
            assertTrue(a.directory.isDirectory)
        } finally { a.close() }
        assertTrue(a.cleanupProven)
        assertNull(a.cleanupFailureReason)
        assertTrue(remaining(parent).isEmpty())
    }

    @Test fun runRecoveryRequiresExplicitUnusedProofAndUnknownRetainsData() = RootfsTarTest.temporary { parent ->
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        File(run.directory, "tmp").mkdir()
        File(run.directory, "tmp/keep").writeText("guest may still use this")
        val directory = run.directory
        run.close()
        assertFalse(run.cleanupProven)
        assertEquals("probe_unknown", run.cleanupFailureReason)
        assertTrue(run.elapsedMillis >= 0)
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
        assertEquals("cleanup_io", run.cleanupFailureReason)
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
        assertEquals("invalid_marker", run.cleanupFailureReason)
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

    @Test fun missingRecordIsNotCalledAProbeFailureAndDoesNotAuthorizeGoneCleanup() = RootfsTarTest.temporary { parent ->
        var probes = 0
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> probes++; true })
        assertTrue(File(parent, ".${run.directory.name}.lease").delete())
        run.close()
        assertFalse(run.cleanupProven)
        assertEquals("record_missing", run.cleanupFailureReason)
        assertEquals(0, probes)
        assertTrue(run.directory.isDirectory)
        val elapsed = run.elapsedMillis
        run.close()
        assertEquals(elapsed, run.elapsedMillis)
        assertFalse(run.cleanupProven)
    }

    @Test(timeout = 10000) fun expiredBudgetBeforeProbeIsDiagnosedWithoutInvokingProbe() = RootfsTarTest.temporary { parent ->
        var probes = 0
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> probes++; true },
            inspect = { file ->
                if (file.name.startsWith("pocketrun-run-")) Thread.sleep(1550)
                LinuxDirectoryLease.readAttributes(file)
            })
        run.close()
        assertFalse(run.cleanupProven)
        assertEquals("budget_exhausted", run.cleanupFailureReason)
        assertEquals(0, probes)
        assertTrue(run.elapsedMillis >= 1500)
        assertTrue(run.directory.isDirectory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        assertFalse(run.cleanupProven) // A later recovery is not a relabel of the original failed close.
    }

    @Test(timeout = 10000) fun successfulProbeCannotHideOverallBudgetExhaustion() = RootfsTarTest.temporary { parent ->
        var probes = 0
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, budget ->
            probes++
            Thread.sleep(budget + 20)
            true
        })
        run.close()
        assertEquals(1, probes)
        assertFalse(run.cleanupProven)
        assertEquals("budget_exhausted", run.cleanupFailureReason)
        assertTrue(run.directory.isDirectory)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
    }

    @Test fun errorDuringCloseStillReleasesOriginalDescriptorsBeforeBecomingRecoverable() = RootfsTarTest.temporary { parent ->
        val fatal = AssertionError("probe failed")
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> throw fatal })
        try { run.close(); fail("must rethrow Error") } catch (error: AssertionError) { assertSame(fatal, error) }
        assertFalse(run.cleanupProven)
        assertEquals("cleanup_error", run.cleanupFailureReason)
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        assertFalse(run.directory.exists()) // The current-process active registry was removed after real close.
        assertFalse(run.cleanupProven)
        assertEquals("cleanup_error", run.cleanupFailureReason)
    }

    @Test fun targetedRecoveryReachesBWithoutProbingUnremovableA() = RootfsTarTest.temporary { parent ->
        val a = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        val b = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        a.close()
        b.close()
        var globalProbes = 0
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> globalProbes++; false })
        assertEquals(1, globalProbes)
        assertTrue(a.directory.isDirectory && b.directory.isDirectory)
        val targets = ArrayList<File>()
        assertTrue(LinuxDirectoryLease.recoverOwned(b.directory, LinuxDirectoryLease.Kind.RUN, unused = { target, budget ->
            targets += target
            assertTrue(budget in 1..1500)
            target == b.directory
        }))
        assertEquals(listOf(b.directory), targets)
        assertFalse(b.directory.exists())
        assertTrue(a.directory.isDirectory)
        assertEquals("probe_unknown", b.cleanupFailureReason) // No relabel of the original failed close.
        assertFalse(b.cleanupProven)
        assertTrue(LinuxDirectoryLease.recoverOwned(a.directory, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true }))
    }

    @Test fun failedUnlockWithSuccessfulOriginalChannelCloseDoesNotPermanentlyPinActiveRegistry() = RootfsTarTest.temporary { parent ->
        var original: java.nio.channels.FileLock? = null
        val failure = java.io.IOException("injected unlock failure")
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false },
            unlock = { held -> original = held; assertTrue(held.isValid); throw failure })
        try { run.close(); fail("must report unlock failure") } catch (error: java.io.IOException) { assertSame(failure, error) }
        val held = checkNotNull(original)
        assertFalse(held.isValid) // Actual FileChannel.close invalidated the original real FileLock.
        assertFalse(held.channel().isOpen)
        assertFalse(run.cleanupProven)
        assertEquals("cleanup_io", run.cleanupFailureReason)
        assertTrue(run.directory.isDirectory)
        assertTrue(LinuxDirectoryLease.recoverOwned(run.directory, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true }))
        assertFalse(run.directory.exists())
        assertFalse(run.cleanupProven)
        assertEquals("cleanup_io", run.cleanupFailureReason)
    }

    @Test fun targetedRecoveryRejectsMalformedMissingOrCorruptedOwnershipWithoutDeleting() = RootfsTarTest.temporary { parent ->
        var probes = 0
        assertFalse(LinuxDirectoryLease.recoverOwned(File(parent, "foreign-directory"), LinuxDirectoryLease.Kind.RUN,
            unused = { _, _ -> probes++; true }))
        val run = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        run.close()
        val marker = File(parent, ".${run.directory.name}.lease")
        marker.writeText("foreign ownership")
        assertFalse(LinuxDirectoryLease.recoverOwned(run.directory, LinuxDirectoryLease.Kind.RUN,
            unused = { _, _ -> probes++; true }))
        assertEquals(0, probes)
        assertTrue(run.directory.isDirectory)
        assertTrue(marker.delete())
        assertFalse(LinuxDirectoryLease.recoverOwned(run.directory, LinuxDirectoryLease.Kind.RUN,
            unused = { _, _ -> probes++; true }))
        assertTrue(run.directory.isDirectory)
    }

    private fun remaining(parent: File) = parent.list().orEmpty().filter { it != ".pocketrun-recovery.lock" }
    private fun startWorker(parent: File, kind: String, ending: String): Process {
        val locations = listOf(LinuxLeaseCrashWorker::class.java, LinuxDirectoryLease::class.java, Unit::class.java)
            .map { File(checkNotNull(checkNotNull(it.protectionDomain).codeSource).location.toURI()).path }
        val classpath = (locations + checkNotNull(System.getProperty("java.class.path")).split(File.pathSeparator)).distinct().joinToString(File.pathSeparator)
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
