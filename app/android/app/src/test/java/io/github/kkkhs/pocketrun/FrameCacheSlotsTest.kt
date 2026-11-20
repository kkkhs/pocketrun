package io.github.kkkhs.pocketrun

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class FrameCacheSlotsTest {
    private lateinit var root: Path

    @Before fun setUp() { root = Files.createTempDirectory("pocketrun-frame-slots-") }
    @After fun tearDown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun displayReplacementCannotDeletePublishedTaskHostFrame() {
        val slots = FrameCacheSlots()
        val taskHost = file("taskhost-a.jpg")
        val displayA = file("display-a.jpg")
        val displayB = file("display-b.jpg")
        slots.replaceTaskHost(taskHost)
        slots.replaceDisplay(displayA)
        slots.replaceDisplay(displayB)
        assertTrue(taskHost.exists())
        assertFalse(displayA.exists())
        assertTrue(displayB.exists())

        val taskHostB = file("taskhost-b.jpg")
        slots.replaceTaskHost(taskHostB)
        assertFalse(taskHost.exists())
        assertTrue(taskHostB.exists())
        assertTrue(displayB.exists())
        slots.clear()
        assertFalse(taskHostB.exists())
        assertFalse(displayB.exists())
    }

    private fun file(name: String) = root.resolve(name).toFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
}
