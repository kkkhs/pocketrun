package io.github.kkkhs.pocketrun

import io.github.kkkhs.pocketrun.taskhost.DemoCsvReport
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class Chapter13WorkspaceTest {
    @Test fun resultIsPublishedAndReadBackOnceThenReportedAsAlreadyPresent() = withWorkspace { session, directory ->
        val bytes = session.readDemoCsvBytes()
        assertEquals(WorkspaceSession.DEMO_CSV, bytes.toString(Charsets.UTF_8))
        val report = DemoCsvReport.parse(bytes)
        assertNull(session.readDemoResultBytes())
        val first = session.publishDemoResult(report.text, {})
        assertEquals("published", first.status)
        assertEquals(report.fileText, directory.resolve("result.txt").readText())
        assertArrayEquals(report.fileText.toByteArray(Charsets.UTF_8), session.readDemoResultBytes())
        assertEquals(DemoCsvReport.digest(report.fileText.toByteArray(Charsets.UTF_8)), first.outputDigest)
        val next = session.publishDemoResult(report.text, {})
        assertEquals("already_present", next.status)
        assertEquals(first.outputDigest, next.outputDigest)
        assertNoTemporary(directory)
    }

    @Test fun differentExistingOrConcurrentUserOutputIsNeverReplaced() = withWorkspace { session, directory ->
        val target = directory.resolve("result.txt")
        target.writeText("existing user data\n")
        assertThrows(IllegalArgumentException::class.java) { session.publishDemoResult("items=2;quantity=5", {}) }
        assertEquals("existing user data\n", target.readText())
        assertNoTemporary(directory)
        assertTrue(target.delete())
        assertThrows(IllegalArgumentException::class.java) {
            session.publishDemoResult("items=2;quantity=5", {}, commit = { source, destination ->
                destination.writeText("concurrent user data\n")
                AtomicSamplePublisher.publish(source, destination)
            })
        }
        assertEquals("concurrent user data\n", target.readText())
        assertNoTemporary(directory)
    }

    @Test fun failedPreCommitGuardAndTemporaryCallbackRemoveOnlyTheirOwnStaging() = withWorkspace { session, directory ->
        val unrelated = directory.resolve("user-kept.tmp").apply { writeText("keep") }
        var calls = 0
        assertThrows(IllegalStateException::class.java) {
            session.publishDemoResult("items=2;quantity=5", guard = {
                check(++calls < 2) { "cancelled before commit" }
            })
        }
        assertEquals(2, calls)
        assertFalse(directory.resolve("result.txt").exists())
        assertNoTemporary(directory)
        assertThrows(IllegalStateException::class.java) {
            session.publishDemoResult("items=2;quantity=5", {}, onTemporary = { error("temporary callback failed") })
        }
        assertFalse(directory.resolve("result.txt").exists())
        assertNoTemporary(directory)
        assertEquals("keep", unrelated.readText())
    }

    @Test fun readsRejectSymlinksAndNonRegularFilesInsteadOfCallingThemAbsent() = withWorkspace { session, directory ->
        val outside = directory.parentFile.resolve("outside.txt").apply { writeText("private outside data\n") }
        val result = directory.resolve("result.txt")
        Files.createSymbolicLink(result.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { session.readDemoResultBytes() }
        assertEquals("private outside data\n", outside.readText())
        assertTrue(result.delete())
        Files.createSymbolicLink(result.toPath(), directory.resolve("missing.txt").toPath())
        assertThrows(IllegalArgumentException::class.java) { session.readDemoResultBytes() }
        assertTrue(result.delete())
        assertTrue(result.mkdir())
        assertThrows(IllegalArgumentException::class.java) { session.readDemoResultBytes() }
        val input = directory.resolve("items.csv")
        assertTrue(input.delete())
        Files.createSymbolicLink(input.toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { session.readDemoCsvBytes() }
    }

    @Test fun boundedReadsAndPrintablePublicationRejectUnsupportedData() = withWorkspace { session, directory ->
        directory.resolve("items.csv").writeBytes(ByteArray(DemoCsvReport.MAX_BYTES + 1))
        assertThrows(IllegalArgumentException::class.java) { session.readDemoCsvBytes() }
        directory.resolve("result.txt").writeBytes(ByteArray(66))
        assertThrows(IllegalArgumentException::class.java) { session.readDemoResultBytes() }
        assertTrue(directory.resolve("result.txt").delete())
        listOf("", "x".repeat(65), "report\n", "界").forEach {
            assertThrows(IllegalArgumentException::class.java) { session.publishDemoResult(it, {}) }
        }
        assertFalse(directory.resolve("result.txt").exists())
        assertNoTemporary(directory)
    }

    private fun assertNoTemporary(directory: File) {
        assertTrue(directory.listFiles()!!.none { it.name.startsWith(".pocketrun-result-") })
    }

    private fun withWorkspace(check: (WorkspaceSession, File) -> Unit) {
        val root = Files.createTempDirectory("pocketrun-ch13-workspace-").toRealPath().toFile()
        try {
            WorkspaceSession(root).use { session ->
                val info = session.prepare()
                check(session, File(info.directory))
            }
        } finally { root.deleteRecursively() }
    }
}
