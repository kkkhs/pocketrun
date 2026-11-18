package io.github.kkkhs.pocketrun

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPipelineTest {
    @Test fun actualPipeReadsRelativeFileAndReturnsExitCodes() {
        val directory = Files.createTempDirectory("pocketrun-pipe").toFile()
        try {
            directory.resolve("items.csv").writeText("one\ntwo\nthree\n")
            CommandPipeline().use { pipe ->
                val result = pipe.execute(pipe.reserve(), directory,
                    listOf(listOf("/bin/cat", "items.csv"), listOf("/usr/bin/wc", "-l")))
                assertEquals("completed", result.status)
                assertEquals("3", result.stdout.trim())
                assertEquals("", result.stderr)
                assertEquals(listOf(0, 0), result.exitCodes)
                assertTrue(pipe.runningProcesses().isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun failingStageIsNotHiddenBySuccessfulLastStage() {
        val directory = Files.createTempDirectory("pocketrun-pipe").toFile()
        try {
            CommandPipeline().use { pipe ->
                val result = pipe.execute(pipe.reserve(), directory,
                    listOf(listOf("/bin/cat", "missing.csv"), listOf("/usr/bin/wc", "-l")))
                assertEquals("failed", result.status)
                assertTrue(result.exitCodes.first() != 0)
                assertTrue(result.stderr.isNotBlank())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun oversizedOutputStopsTheChildAndCanRunAgain() {
        val directory = Files.createTempDirectory("pocketrun-pipe").toFile()
        try {
            CommandPipeline().use { pipe ->
                val result = pipe.execute(pipe.reserve(), directory,
                    listOf(listOf("/usr/bin/head", "-c", "200000", "/dev/zero")))
                assertEquals("output_limit", result.status)
                assertTrue(result.stdout.toByteArray().size <= CommandPipeline.MAX_OUTPUT_BYTES)
                assertTrue(pipe.runningProcesses().isEmpty())
                val next = pipe.execute(pipe.reserve(), directory, listOf(listOf("/usr/bin/id", "-u")))
                assertEquals("completed", next.status)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun timeoutIsBoundedAndChildrenAreGone() {
        val directory = Files.createTempDirectory("pocketrun-pipe").toFile()
        try {
            CommandPipeline().use { pipe ->
                val run = pipe.reserve()
                val result = pipe.execute(run, directory, listOf(listOf("/bin/sleep", "5")), 100)
                assertEquals("timeout", result.status)
                assertTrue(result.durationMillis < 2000)
                assertTrue(run.processes.none { it.isAlive })
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelBeforeWorkerStartsDoesNotLaunchAChild() {
        val directory = Files.createTempDirectory("pocketrun-pipe").toFile()
        try {
            CommandPipeline().use { pipe ->
                val run = pipe.reserve()
                assertTrue(pipe.cancel())
                val result = pipe.execute(run, directory, listOf(listOf("/bin/sleep", "5")))
                assertEquals("cancelled", result.status)
                assertTrue(run.processes.isEmpty())
                assertFalse(pipe.cancel())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun cancelAndCloseReclaimRunningChildren() {
        val directory = Files.createTempDirectory("pocketrun-pipe").toFile()
        val worker = Executors.newSingleThreadExecutor()
        try {
            for (close in listOf(false, true)) {
                CommandPipeline().use { pipe ->
                    val run = pipe.reserve()
                    val future = worker.submit<CommandPipeline.Result> {
                        pipe.execute(run, directory, listOf(listOf("/bin/sleep", "5")))
                    }
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                    while (pipe.runningProcesses().isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
                    assertTrue(pipe.runningProcesses().isNotEmpty())
                    if (close) pipe.close() else assertTrue(pipe.cancel())
                    assertEquals("cancelled", future.get(2, TimeUnit.SECONDS).status)
                    assertTrue(run.processes.none { it.isAlive })
                    if (close) assertThrows(IllegalStateException::class.java) { pipe.reserve() }
                }
            }
        } finally {
            worker.shutdownNow()
            directory.deleteRecursively()
        }
    }
}
