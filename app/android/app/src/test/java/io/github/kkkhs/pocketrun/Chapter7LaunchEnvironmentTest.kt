package io.github.kkkhs.pocketrun

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Chapter7LaunchEnvironmentTest {
    @Test fun trustedEnvironmentIsExplicitAndInheritedValuesAreNotPassed() {
        val root = Files.createTempDirectory("linux-env-").toFile()
        try {
            CommandPipeline().use { pipeline ->
                val result = pipeline.execute(pipeline.reserve(), root, listOf(listOf("/usr/bin/env")),
                    env = mapOf("POCKETRUN_TEST" to "explicit"), clearEnvironment = true)
                assertEquals("completed", result.status)
                assertEquals("POCKETRUN_TEST=explicit", result.stdout.trim())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun boundedInternalLinuxArgumentsAreNotAJsCommandInterface() {
        val root = Files.createTempDirectory("linux-argv-").toFile()
        try {
            CommandPipeline().use { pipeline ->
                val words = List(20) { "fixed$it" }
                val result = pipeline.execute(pipeline.reserve(), root, listOf(listOf("/bin/echo") + words))
                assertEquals("completed", result.status)
                assertEquals(words.joinToString(" "), result.stdout.trim())
                val run = pipeline.reserve()
                val rejected = pipeline.execute(run, root, listOf(listOf("/bin/echo") + List(96) { "fixed" }))
                assertEquals("failed", rejected.status)
                assertTrue(run.processes.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeEnvironmentIsRejectedBeforeSpawn() {
        val root = Files.createTempDirectory("linux-env-rejected-").toFile()
        try {
            CommandPipeline().use { pipeline ->
                val run = pipeline.reserve()
                val result = pipeline.execute(run, root, listOf(listOf("/usr/bin/env")),
                    env = mapOf("INVALID-KEY" to "not launched"), clearEnvironment = true)
                assertEquals("failed", result.status)
                assertTrue(run.processes.isEmpty())
                assertFalse(result.stdout.contains("not launched"))
            }
        } finally { root.deleteRecursively() }
    }
}
