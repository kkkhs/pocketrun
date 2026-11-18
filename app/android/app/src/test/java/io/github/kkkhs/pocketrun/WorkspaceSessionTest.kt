package io.github.kkkhs.pocketrun

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceSessionTest {
    @Test fun prepareUsesPrivateDirectoryAndDoesNotOverwriteExistingData() {
        val root = Files.createTempDirectory("pocketrun-workspace").toFile()
        try {
            WorkspaceSession(root).use { session ->
                val first = session.prepare()
                assertTrue(first.sampleCreated)
                assertEquals(root.canonicalFile.resolve("workspaces/demo").path, first.directory)
                val file = root.resolve("workspaces/demo/items.csv")
                assertEquals(WorkspaceSession.DEMO_CSV, file.readText())
                file.writeText("saved user data\n")
                assertFalse(session.prepare().sampleCreated)
                session.close()
                assertEquals("saved user data\n", file.readText())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun workspaceAndSampleSymlinksCannotEscape() {
        val root = Files.createTempDirectory("pocketrun-workspace").toFile()
        val outside = Files.createTempDirectory("pocketrun-outside").toFile()
        try {
            Files.createSymbolicLink(root.resolve("workspaces").toPath(), outside.toPath())
            WorkspaceSession(root).use { session ->
                assertThrows(IllegalArgumentException::class.java) { session.prepare() }
            }
            assertFalse(outside.resolve("demo").exists())
            root.resolve("workspaces").delete()
            WorkspaceSession(root).use { session ->
                session.prepare()
                val sample = root.resolve("workspaces/demo/items.csv")
                sample.delete()
                val external = outside.resolve("secret.csv").apply { writeText("secret\n") }
                Files.createSymbolicLink(sample.toPath(), external.toPath())
                assertThrows(IllegalArgumentException::class.java) { session.reserve() }
            }
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }

    @Test fun unpreparedAndOversizedFilesAreRejectedBeforeAJobIsReserved() {
        val root = Files.createTempDirectory("pocketrun-workspace").toFile()
        try {
            WorkspaceSession(root).use { session ->
                assertThrows(IllegalArgumentException::class.java) { session.reserve() }
                session.prepare()
                root.resolve("workspaces/demo/items.csv").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES + 1))
                assertThrows(IllegalArgumentException::class.java) { session.reserve() }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun changedFileAfterReservationFailsWithoutStartingCommands() {
        val root = Files.createTempDirectory("pocketrun-workspace").toFile()
        try {
            WorkspaceSession(root, listOf(listOf("/bin/cat", "items.csv"), listOf("/usr/bin/wc", "-l"))).use { session ->
                session.prepare()
                val run = session.reserve()
                root.resolve("workspaces/demo/items.csv").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES + 1))
                val result = session.runDemo(run)
                assertEquals("failed", result.status)
                assertTrue(result.stderr.contains("64 KiB"))
                assertTrue(run.processes.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }
}
