package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LinuxSessionTest {
    @Test fun fixedArgvUsesOnlyApkElfAndFixedBindingsWithoutIdentityEmulation() = RootfsTarTest.temporary { files ->
        val installer = RootfsInstallerTest.installer(files)
        val argv = LinuxSession.demoArgv(File(files, "rootfs"), File(files, "workspaces/demo"),
            File(files, "tmp"), installer.nativeDirectory, installer.runtime.bindings)
        assertEquals(File(installer.nativeDirectory, "libproot.so").path, argv.first())
        assertTrue(argv.size <= 96)
        assertTrue(argv.contains("--kill-on-exit"))
        assertFalse(argv.contains("-0"))
        assertFalse(argv.any { it.contains("linker64") || it.contains("LD_PRELOAD") })
        assertTrue(argv.contains("${File(files, "workspaces/demo").path}:/workspace"))
        assertEquals(LinuxSession.DEMO_SCRIPT, argv.last())
        val env = LinuxSession.demoEnvironment(installer.nativeDirectory, File(files, "tmp"), 12345)
        assertEquals("12345", env["POCKETRUN_EXPECTED_PARENT_PID"])
        assertEquals(File(installer.nativeDirectory, "libproot_loader.so").path, env["PROOT_LOADER"])
        val record = File(files, "tracer.identity")
        assertEquals(record.path, env["POCKETRUN_TRACER_RECORD"])
        assertFalse(record.toPath().startsWith(File(files, "tmp").toPath()))
        assertFalse(record.exists()) // The ENV builder does not create a second controller or write identity data.
        assertEquals(env, LinuxSession.demoEnvironment(installer.nativeDirectory, File(files, "tmp"), 12345, record))
        RootfsTarTest.expectFailure {
            LinuxSession.demoEnvironment(installer.nativeDirectory, File(files, "tmp"), 12345, File(files, "tmp/tracer.identity"))
        }
        assertFalse(env.containsKey("LD_LIBRARY_PATH"))
        assertFalse(env.containsKey("LD_PRELOAD"))
        assertFalse(env.containsKey("PROOT_NO_SECCOMP"))
    }

    @Test fun parsesActualGuestFieldsAndAllFiveStageExitsWithoutAssumingSuccess() {
        val demo = LinuxSession.parseDemo("3.22.8\n/workspace\n10469\n      3\nPR_EXITS:0,0,0,0,0\n")!!
        assertEquals(10469, demo.uid)
        assertEquals(3, demo.count)
        assertEquals(listOf(0, 0, 0, 0, 0), demo.exits)
        assertEquals(listOf(0, 0, 0, 1, 0), LinuxSession.parseDemo("3.22.8\n/workspace\n10469\n0\nPR_EXITS:0,0,0,1,0\n")!!.exits)
        assertNull(LinuxSession.parseDemo("3.22.8\n/workspace\n10469\n3\n"))
        assertNull(LinuxSession.parseDemo("3.22.8\n/workspace\nroot\n3\nPR_EXITS:0,0,0,0,0\n"))
        assertNull(LinuxSession.parseDemo("3.22.8\n/workspace\n10469\n3\nPR_EXITS:0,0,0\n"))
    }

    @Test fun prepareBusyTokenBoundCancelCloseAndPreservedWorkspace() = RootfsTarTest.temporary { files ->
        val session = LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345)
        session.use {
            val first = session.reservePrepare("prepare-1")
            RootfsTarTest.expectFailure { session.reservePrepare("prepare-2") }
            assertFalse(session.cancel("prepare-2"))
            val info = session.prepare(first)
            assertTrue(info.installedNow)
            File(info.workspaceDirectory, "items.csv").writeText("user\ndata\n")
            val again = session.prepare(session.reservePrepare("prepare-2"))
            assertFalse(again.installedNow)
            assertFalse(again.sampleCreated)
            assertEquals("user\ndata\n", File(info.workspaceDirectory, "items.csv").readText())
            val run = session.reserve("run-1")
            assertFalse(session.cancel("prepare-1"))
            assertTrue(session.cancel("run-1"))
            val cancelled = session.runDemo(run)
            assertEquals("cancelled", cancelled.status)
            assertTrue(cancelled.exitCodes.isEmpty()) // JVM did not spawn an ARM/Linux process.
            assertTrue(File(files, "linux/runs").list().orEmpty().all { it == ".pocketrun-recovery.lock" })
            val newer = session.reserve("run-2")
            assertFalse(session.cancel("run-1"))
            assertTrue(session.cancel("run-2"))
            assertEquals("cancelled", session.runDemo(newer).status)
        }
        RootfsTarTest.expectFailure { session.reservePrepare("after-close") }
    }

    @Test fun cleanupProofIsReportedWithoutLosingCommandOutputOrPretendingJvmIsAndroid() = RootfsTarTest.temporary { parent ->
        val command = LinuxSession.Result("fixture", "fixed", "completed", "retained output", "retained error",
            listOf(0), listOf(0, 0, 0, 0, 0), 1, 10469, "3.22.8", 10469, "/workspace", 3)
        val unknown = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> false })
        val unproven = LinuxSession.cleanupResult(command, unknown)
        assertEquals(command.copy(cleanupStatus = "unproven"), unproven)
        assertTrue(unknown.directory.exists())
        val proven = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        val done = LinuxSession.cleanupResult(command, proven)
        assertEquals(command.copy(cleanupStatus = "done"), done)
        assertFalse(proven.directory.exists())
        // The injected booleans exercise branches only, not a real Android /proc proof.
    }

    @Test fun failedPipelineProofCannotBeWashedIntoDoneByASeparateUnusedDirectoryProof() = RootfsTarTest.temporary { parent ->
        val command = LinuxSession.Result("fixture", "fixed", "completed", "keep stdout", "keep stderr", listOf(0),
            listOf(0, 0, 0, 0, 0), 1, 10469, "3.22.8", 10469, "/workspace", 3)
        val independentProof = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        val result = LinuxSession.cleanupResult(command, independentProof, pipelineCleanupProven = false)
        assertEquals(command.copy(cleanupStatus = "unproven"), result)
        assertTrue(independentProof.cleanupProven)

        var probes = 0
        val productionGated = LinuxDirectoryLease.allocate(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ ->
            val pipelineProof = false
            pipelineProof && run { probes++; true }
        })
        val retained = LinuxSession.cleanupResult(command, productionGated, pipelineCleanupProven = false)
        assertEquals("unproven", retained.cleanupStatus)
        assertTrue(productionGated.directory.exists())
        assertEquals(0, probes)
        // Injected proof values exercise the composition only, not a real Android kill/reap.
        LinuxDirectoryLease.recover(parent, LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
    }

    @Test fun trustedPlatformAliasCanBeNormalizedOnceWithoutRejectingThePhysicalPrivateBase() = RootfsTarTest.temporary { parent ->
        val physical = File(parent, "physical").apply { mkdir() }
        val alias = File(parent, "platform-alias")
        java.nio.file.Files.createSymbolicLink(alias.toPath(), physical.toPath())
        val trustedBase = alias.canonicalFile
        LinuxSession(trustedBase, RootfsInstallerTest.installer(trustedBase), 10469, 12345).use { session ->
            val info = session.prepare(session.reservePrepare("trusted-alias"))
            assertTrue(info.workspaceDirectory.startsWith(physical.path + "/"))
            assertFalse(info.workspaceDirectory.startsWith(alias.path + "/"))
        }
    }

    @Test fun fatalErrorAfterLeaseAllocationRethrowsButReleasesLeasePipelineAndCurrentSlot() = RootfsTarTest.temporary { files ->
        val failure = AssertionError("before fixed demo execution")
        var owned: File? = null
        LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345,
            canRemoveRun = { _, _ -> true }, beforeDemoExecution = { directory -> owned = directory; throw failure }).use { session ->
            session.prepare(session.reservePrepare("prepare-error"))
            try { session.runDemo(session.reserve("fatal-run")); fail("must rethrow Error") }
            catch (error: AssertionError) { assertSame(failure, error) }
            assertNotNull(owned)
            assertFalse(owned!!.exists())
            val next = session.reserve("after-error")
            assertTrue(session.cancel(next.token))
            assertEquals("cancelled", session.runDemo(next).status)
        }
    }

    @Test fun cleanupErrorIsSuppressedWithoutMaskingFatalErrorOrLeakingTheCurrentSlot() = RootfsTarTest.temporary { files ->
        val primary = AssertionError("primary")
        val cleanup = AssertionError("cleanup")
        LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345,
            canRemoveRun = { _, _ -> throw cleanup }, beforeDemoExecution = { throw primary }).use { session ->
            session.prepare(session.reservePrepare("prepare"))
            try { session.runDemo(session.reserve("fatal")); fail("must rethrow Error") }
            catch (error: AssertionError) { assertSame(primary, error); assertTrue(error.suppressed.contains(cleanup)) }
            val next = session.reserve("after")
            session.cancel(next.token)
            assertEquals("cancelled", session.runDemo(next).status)
            LinuxDirectoryLease.recover(File(files, "linux/runs"), LinuxDirectoryLease.Kind.RUN, unused = { _, _ -> true })
        }
    }

    @Test fun cancelBeforePrepareAndInvalidTokensNeverPublish() = RootfsTarTest.temporary { files ->
        LinuxSession(files, RootfsInstallerTest.installer(files), 10469, 12345).use { session ->
            RootfsTarTest.expectFailure { session.reservePrepare("bad/token") }
            val op = session.reservePrepare("cancel-prepare")
            assertTrue(session.cancel(op.token))
            RootfsTarTest.expectFailure { session.prepare(op) }
            assertFalse(File(files, "linux").exists())
        }
    }
}
