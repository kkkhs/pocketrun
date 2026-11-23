package io.github.kkkhs.pocketrun.taskhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TaskHostJsonTest {
    @Test
    fun parsesOnlyFlatStrictValuesWithExactIntegerTypes() {
        val parsed = TaskHostJson.parseObject(
            "{\"action\":\"demo_tap\",\"expectedDisplayId\":9,\"expectedDisplayEpoch\":4294967296,\"x\":3,\"ok\":true}")
        assertEquals("demo_tap", parsed["action"])
        assertTrue(parsed["expectedDisplayId"] is Int)
        assertTrue(parsed["expectedDisplayEpoch"] is Long)
        assertEquals(3, parsed["x"])
        assertEquals(true, parsed["ok"])
    }

    @Test
    fun rejectsDuplicateNestedFloatingAndTrailingInput() {
        invalid("duplicate") { TaskHostJson.parseObject("{\"x\":1,\"x\":2}") }
        invalid("flat") { TaskHostJson.parseObject("{\"x\":{}}") }
        invalid("Floating") { TaskHostJson.parseObject("{\"x\":1.0}") }
        invalid("Trailing") { TaskHostJson.parseObject("{} false") }
    }

    @Test
    fun rejectsOversizeAndOutOfRangeIntegerBeforeCore() {
        invalid("4096") { TaskHostJson.parseObject("{\"x\":\"${"z".repeat(4096)}\"}") }
        invalid("range") { TaskHostJson.parseObject("{\"x\":9223372036854775808}") }
    }

    @Test
    fun decodesJsonEscapesButDoesNotAcceptControlCharacters() {
        assertEquals("PocketRun_TaskHost_9",
            TaskHostJson.parseObject("{\"text\":\"PocketRun_TaskHost_9\"}")["text"])
        assertEquals("a\nb", TaskHostJson.parseObject("{\"text\":\"a\\nb\"}")["text"])
        invalid("Control") { TaskHostJson.parseObject("{\"text\":\"a\nb\"}") }
    }

    private fun invalid(fragment: String, block: () -> Unit) {
        try {
            block()
            fail("Expected invalid JSON")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains(fragment, ignoreCase = true))
        }
    }
}
