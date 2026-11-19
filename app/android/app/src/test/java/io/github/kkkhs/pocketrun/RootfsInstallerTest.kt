package io.github.kkkhs.pocketrun

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class RootfsInstallerTest {
    @Test fun atomicInstallAndRepeatPreparePreserveWorkspaceAndOtherStages() = RootfsTarTest.temporary { files ->
        val workspace = File(files, "workspaces/demo").apply { mkdirs() }
        File(workspace, "items.csv").writeText("user,data\n")
        val foreign = File(files, "linux/stage/another-run").apply { mkdirs() }
        File(foreign, "keep").writeText("not owned")
        val installer = installer(files)
        val first = installer.prepare()
        assertTrue(first.created)
        assertEquals("3.22.8\n", File(first.root, "etc/alpine-release").readText())
        assertEquals(0L, File(first.root, "bin/busybox").length())
        assertFalse(installer.prepare().created)
        assertEquals("user,data\n", File(workspace, "items.csv").readText())
        assertEquals("not owned", File(foreign, "keep").readText())
        assertEquals(listOf("another-run"), stageEntries(files))
    }

    @Test fun failedPublicationCleansOnlyOwnStageAndCanRetry() = RootfsTarTest.temporary { files ->
        val installer = installer(files)
        RootfsTarTest.expectFailure { installer.prepare(commit = { throw IllegalStateException("crash before publication") }) }
        assertTrue(stageEntries(files).isEmpty())
        assertTrue(installer.prepare().created)
    }

    @Test fun cancelledCopyLeavesNoPartialPublishedRootfs() = RootfsTarTest.temporary { files ->
        val installer = installer(files)
        RootfsTarTest.expectFailure {
            installer.prepare(guard = {
                val stages = File(files, "linux/stage").listFiles().orEmpty()
                if (stages.any { File(it, "data.tar.gz").length() > 0 }) throw CancellationException()
            })
        }
        assertTrue(stageEntries(files).isEmpty())
        assertFalse(File(files, "linux").listFiles()!!.any { it.name.startsWith("rootfs-") })
        assertTrue(installer.prepare().created)
    }

    @Test fun wrongArchiveShaAndCorruptedPublishedDataAreRejectedNotReplaced() = RootfsTarTest.temporary { files ->
        val correct = installer(files)
        val broken = RootfsInstaller(files, correct.nativeDirectory, correct.runtime.copy(dataSha256 = "0".repeat(64)),
            { RootfsTarTest.mainDirectory().resolve("assets/linux/alpine-data.bin").inputStream() })
        RootfsTarTest.expectFailure { broken.prepare() }
        assertTrue(stageEntries(files).isEmpty())
        val installed = correct.prepare()
        val version = File(installed.root, "etc/alpine-release")
        version.writeText("broken\n")
        RootfsTarTest.expectFailure { correct.prepare() }
        assertEquals("broken\n", version.readText())
    }

    @Test fun symlinkedInstallationBaseIsRejectedWithoutTouchingTarget() = RootfsTarTest.temporary { files ->
        RootfsTarTest.temporary { outside ->
            Files.createSymbolicLink(File(files, "linux").toPath(), outside.toPath())
            RootfsTarTest.expectFailure { installer(files).prepare() }
            assertTrue(outside.list()!!.isEmpty())
        }
    }

    @Test fun existingInstallationChecksAssetWithoutAllocatingACopyStage() = RootfsTarTest.temporary { files ->
        val first = installer(files)
        first.prepare()
        var opened = false
        val reused = RootfsInstaller(files, first.nativeDirectory, first.runtime, { name ->
            opened = true
            assertTrue(stageEntries(files).isEmpty())
            RootfsTarTest.mainDirectory().resolve("assets/$name").inputStream()
        })
        assertFalse(reused.prepare().created)
        assertTrue(opened)
        assertTrue(stageEntries(files).isEmpty())
    }

    companion object {
        internal fun stageEntries(files: File) = File(files, "linux/stage").list().orEmpty()
            .filter { it != ".pocketrun-recovery.lock" }
        internal fun installer(files: File): RootfsInstaller {
            val main = RootfsTarTest.mainDirectory()
            val native = main.resolve("jniLibs/arm64-v8a").canonicalFile
            val manifest = main.resolve("assets/linux/runtime.json")
            // Test reads the real packaged fixed manifest, not an invented installation fixture.
            val text = manifest.readText()
            val artifacts = Regex("\\\"name\\\"\\s*:\\s*\\\"(lib[^\\\"]+\\.so)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([a-f0-9]{64})\\\"")
                .findAll(text).map { LinuxArtifact(it.groupValues[1], it.groupValues[2]) }.toList()
            val bindings = Regex("\\\"apk_basename\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"guest_path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .findAll(text).map { LinuxBinding(it.groupValues[1], it.groupValues[2]) }.toList()
            val runtime = LinuxRuntime(RootfsInstaller.hash(manifest), artifacts, bindings,
                "linux/alpine-data.bin", "6975f46c20ad8b950f164c3ea3fc524f416d82b232539f3c266c5e9aef82a149")
            return RootfsInstaller(files.canonicalFile, native, runtime, { main.resolve("assets/$it").inputStream() })
        }
    }
}
