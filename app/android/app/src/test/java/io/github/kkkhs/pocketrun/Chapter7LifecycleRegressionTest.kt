package io.github.kkkhs.pocketrun

import java.io.File
import java.io.FilterInputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Real packaged data/hash and production state-machine regressions. Not ARM/device execution evidence. */
class Chapter7LifecycleRegressionTest {
    private fun payloadNames(directory: File) = directory.list().orEmpty().filter { it != ".pocketrun-recovery.lock" }

    @Test fun missingRunProofPreservesLeasePayloadAndReportsUnproven() = RootfsTarTest.temporary { files ->
        val owned = LinuxDirectoryLease.allocate(File(files, "runs"), LinuxDirectoryLease.Kind.RUN)
        val payload = File(owned.directory, "retained").apply { writeText("do not delete without process proof") }
        val result = LinuxSession.Result("proof-negative", "0".repeat(64), "completed", "output", "",
            listOf(0), listOf(0), 1, 10469, "3.22.8", 10469, "/workspace", 3)
        val settled = LinuxSession.cleanupResult(result, owned)
        assertEquals("unproven", settled.cleanupStatus)
        assertEquals("completed", settled.status)
        assertEquals("output", settled.stdout)
        assertFalse(owned.cleanupProven)
        assertTrue(payload.isFile)
    }

    @Test fun uncheckedFailureReleasesReservedSlotAndKernelLeaseWithoutSpawning() = RootfsTarTest.temporary { files ->
        var allocated: File? = null
        val session = LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345,
            beforeDemoExecution = { directory -> allocated = directory; throw AssertionError("real pre-exec Error boundary") })
        session.use {
            session.prepare(session.reservePrepare("error-prepare"))
            val operation = session.reserve("error-operation")
            assertThrows(AssertionError::class.java) { session.runDemo(operation) }
            assertTrue(operation.run!!.processes.isEmpty())
            assertFalse(session.cancel(operation.token))
            val directory = checkNotNull(allocated)
            // Lack of process proof keeps payload, but its kernel FileLock/FileChannel must be released.
            assertTrue(directory.isDirectory)
            val leaseFile = File(directory.parentFile, ".${directory.name}.lease")
            FileChannel.open(leaseFile.toPath(), WRITE).use { channel ->
                val lock = channel.tryLock()
                assertNotNull(lock)
                lock!!.release()
            }
            val next = session.reserve("after-error")
            assertTrue(session.cancel(next.token))
            val result = session.runDemo(next)
            assertEquals("cancelled", result.status)
            assertEquals("done", result.cleanupStatus)
            assertTrue(next.run!!.processes.isEmpty())
        }
    }

    @Test fun stalePrepareCannotFinishOrCancelTheNewEpoch() = RootfsTarTest.temporary { files ->
        LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345).use { session ->
            val old = session.reservePrepare("old-epoch")
            session.prepare(old)
            val next = session.reservePrepare("new-epoch")
            assertThrows(IllegalStateException::class.java) { session.prepare(old) }
            assertFalse(session.cancel("old-epoch"))
            assertEquals("new-epoch", session.prepare(next).token)
        }
    }

    @Test fun cancellationBeforePreparationNeverAllocatesAStage() = RootfsTarTest.temporary { files ->
        LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345).use { session ->
            val op = session.reservePrepare("cancelled")
            assertTrue(session.cancel(op.token))
            assertThrows(CancellationException::class.java) { session.prepare(op) }
            assertFalse(File(files, "linux").exists())
            assertFalse(File(files, "workspaces").exists())
            assertTrue(session.prepare(session.reservePrepare("retry")).installedNow)
        }
    }

    @Test fun closeDuringRealArchiveReadPreventsPublication() = RootfsTarTest.temporary { files ->
        val normal = RootfsInstallerTest.installer(files)
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val installer = RootfsInstaller(files, normal.nativeDirectory, normal.runtime, { name ->
            object : FilterInputStream(RootfsTarTest.mainDirectory().resolve("assets/$name").inputStream()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    entered.countDown()
                    check(proceed.await(3, TimeUnit.SECONDS))
                    return super.read(bytes, offset, length)
                }
            }
        })
        val session = LinuxSession(files, installer, 10469, 12345)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val operation = session.reservePrepare("close-in-flight")
            val future = worker.submit<LinuxSession.Info> { session.prepare(operation) }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.close()
            proceed.countDown()
            assertTrue(assertThrows(ExecutionException::class.java) { future.get(3, TimeUnit.SECONDS) }.cause is CancellationException)
            assertTrue(payloadNames(File(files, "linux/stage")).isEmpty())
            assertTrue(File(files, "linux").listFiles().orEmpty().none { it.name.startsWith("rootfs-") })
            assertThrows(IllegalStateException::class.java) { session.reservePrepare("closed") }
        } finally { proceed.countDown(); session.close(); worker.shutdownNow() }
    }

    @Test fun reserveDeadlineExpiresBeforeAnyArmProcessCanSpawn() = RootfsTarTest.temporary { files ->
        LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345).use { session ->
            session.prepare(session.reservePrepare("prepare"))
            val expired = session.reserve("expired")
            Thread.sleep(CommandPipeline.TIMEOUT_MS + 50)
            val result = session.runDemo(expired)
            assertEquals("timeout", result.status)
            assertTrue(result.exitCodes.isEmpty())
            assertTrue(payloadNames(File(files, "linux/runs")).isEmpty())
            val retry = session.reserve("after-expired")
            assertTrue(session.cancel(retry.token))
            assertEquals("cancelled", session.runDemo(retry).status)
        }
    }

    @Test fun truncatedRealArchiveLeavesNoPublicationAndCanRetry() = RootfsTarTest.temporary { files ->
        val normal = RootfsInstallerTest.installer(files)
        val original = RootfsTarTest.mainDirectory().resolve("assets/${normal.runtime.dataName}").readBytes()
        val broken = RootfsInstaller(files, normal.nativeDirectory, normal.runtime, {
            original.copyOf(original.size / 2).inputStream()
        })
        assertThrows(IllegalArgumentException::class.java) { broken.prepare() }
        assertTrue(payloadNames(File(files, "linux/stage")).isEmpty())
        assertTrue(File(files, "linux").listFiles().orEmpty().none { it.name.startsWith("rootfs-") })
        assertTrue(normal.prepare().created)
    }

    @Test fun productionEnvironmentDoesNotSelectTheTestOnlyPtraceFallback() {
        val env = LinuxSession.demoEnvironment(File("/readonly/native"), File("/private/tmp"), 12345)
        assertEquals("12345", env["POCKETRUN_EXPECTED_PARENT_PID"])
        assertFalse(env.containsKey("PROOT_NO_SECCOMP"))
        assertFalse(env.containsKey("LD_PRELOAD"))
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
    }
}
