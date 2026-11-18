package io.github.kkkhs.pocketrun

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Chapter6RegressionTest {
    @Test fun realNoReplacePublicationDoesNotReplaceConcurrentUserFile() {
        val root = Files.createTempDirectory("workspace-no-replace").toFile()
        try {
            val sample = root.resolve("workspaces/demo/items.csv")
            WorkspaceSession(root, beforeSamplePublish = {
                assertFalse(sample.exists())
                sample.writeText("concurrent user data\n")
            }).use { session ->
                assertFalse(session.prepare().sampleCreated)
                assertEquals("concurrent user data\n", sample.readText())
                assertTrue(sample.parentFile!!.listFiles()!!.none { it.name.startsWith(".pocketrun-sample-") })
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun truncatedMultibyteOutputDoesNotExpandPastReturnedUtf8Limit() {
        val root = Files.createTempDirectory("pipeline-utf8-bound").toFile()
        try {
            root.resolve("unicode").writeText("a".repeat(CommandPipeline.MAX_OUTPUT_BYTES - 1) + "汉")
            root.resolve("invalid").writeBytes(ByteArray(CommandPipeline.MAX_OUTPUT_BYTES) { 0xff.toByte() })
            CommandPipeline().use { pipeline ->
                for (stderr in listOf(false, true)) {
                    val command = if (stderr) listOf("/bin/sh", "-c", "exec /bin/cat unicode >&2")
                        else listOf("/bin/cat", "unicode")
                    val result = pipeline.execute(pipeline.reserve(), root, listOf(command))
                    assertEquals("output_limit", result.status)
                    val returned = if (stderr) result.stderr else result.stdout
                    assertTrue(returned.toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                    val invalidCommand = if (stderr) listOf("/bin/sh", "-c", "exec /bin/cat invalid >&2")
                        else listOf("/bin/cat", "invalid")
                    val invalid = pipeline.execute(pipeline.reserve(), root, listOf(invalidCommand))
                    assertEquals("output_limit", invalid.status)
                    assertTrue((if (stderr) invalid.stderr else invalid.stdout).toByteArray(Charsets.UTF_8).size <= CommandPipeline.MAX_OUTPUT_BYTES)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun closeBetweenReservationAndExecutionCannotSpawnOrReopen() {
        val root = Files.createTempDirectory("pipeline-closed-before-start").toFile()
        try {
            CommandPipeline().use { pipeline ->
                val run = pipeline.reserve()
                pipeline.close()
                val result = pipeline.execute(run, root, listOf(listOf("/usr/bin/touch", "unexpected")))
                assertEquals("cancelled", result.status)
                assertTrue(run.processes.isEmpty())
                assertFalse(root.resolve("unexpected").exists())
                assertThrows(IllegalStateException::class.java) { pipeline.reserve() }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun failedSecondSpawnReapsFirstRealChildAndReservationCanBeReused() {
        val root = Files.createTempDirectory("pipeline-partial-spawn").toFile()
        try {
            CommandPipeline().use { pipeline ->
                val run = pipeline.reserve()
                val result = pipeline.execute(run, root,
                    listOf(listOf("/bin/sleep", "30"), listOf(root.resolve("missing-executable").path)))
                assertEquals("failed", result.status)
                assertEquals(1, run.processes.size)
                assertTrue(run.processes.none { it.isAlive })
                assertTrue(pipeline.runningProcesses().isEmpty())
                assertEquals("completed", pipeline.execute(pipeline.reserve(), root,
                    listOf(listOf("/usr/bin/touch", "rerun"))).status)
                assertTrue(root.resolve("rerun").isFile)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun expiredValidationNeverStartsSideEffectingCommand() {
        val root = Files.createTempDirectory("pipeline-deadline").toFile()
        try {
            CommandPipeline().use { pipeline ->
                val run = pipeline.reserve()
                val result = pipeline.execute(run, root, listOf(listOf("/usr/bin/touch", "unexpected")), 50) {
                    Thread.sleep(150)
                }
                assertEquals("timeout", result.status)
                assertTrue(run.processes.isEmpty())
                assertFalse(root.resolve("unexpected").exists())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedStagingIsNotPublishedAsEmptySample() {
        val root = Files.createTempDirectory("workspace-publish").toFile()
        try {
            val directory = root.resolve("workspaces/demo").apply { mkdirs() }
            directory.resolve(".pocketrun-sample-interrupted.tmp").createNewFile()
            WorkspaceSession(root).use { session ->
                val info = session.prepare()
                assertTrue(info.sampleCreated)
                assertEquals(WorkspaceSession.DEMO_CSV, directory.resolve("items.csv").readText())
                assertTrue(directory.listFiles()!!.none { it.name.startsWith(".pocketrun-sample-") })
                directory.resolve("items.csv").writeText("")
                assertFalse(session.prepare().sampleCreated)
                assertEquals(0, directory.resolve("items.csv").length())
            }
        } finally { root.deleteRecursively() }
    }
}
