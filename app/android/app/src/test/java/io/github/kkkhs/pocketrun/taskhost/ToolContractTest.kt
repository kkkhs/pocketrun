package io.github.kkkhs.pocketrun.taskhost

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ToolContractTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("pocketrun-ch9-contract-").toRealPath()
        Files.writeString(root.resolve("items.csv"), "item,count\nalpha,2\n")
    }

    @After
    fun tearDown() {
        Files.walk(root).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun onlyThreeExactToolNamesAreAccepted() {
        assertEquals(ParsedAction.DeviceInfo,
            ToolSchema.validate(RawToolCall("android.device_info", emptyMap())))
        val linux = ToolSchema.validate(RawToolCall("linux.count_demo_csv", emptyMap()))
        assertEquals(ParsedAction.CountDemoCsv, linux)
        assertTrue(linux.approvalRequired)
        validation("UNKNOWN_TOOL") {
            ToolSchema.validate(RawToolCall("linux.shell", emptyMap()))
        }
    }

    @Test
    fun unknownExtraMissingAndNestedArgumentsAreRejected() {
        validation("UNKNOWN_ARGUMENT") {
            ToolSchema.validate(RawToolCall("android.device_info", mapOf("actor" to "app")))
        }
        validation("MISSING_ARGUMENT") {
            ToolSchema.validate(RawToolCall("android.virtual_display_observe_or_demo_input",
                mapOf("action" to "observe", "expectedDisplayId" to 7)))
        }
        validation("WRONG_ARGUMENT_TYPE") {
            ToolSchema.validate(RawToolCall("android.virtual_display_observe_or_demo_input",
                mapOf("action" to mapOf("nested" to true), "expectedDisplayId" to 7,
                    "expectedDisplayEpoch" to 2L)))
        }
    }

    @Test
    fun numericTypesAreStrictAndNotCoerced() {
        validation("WRONG_ARGUMENT_TYPE") {
            ToolSchema.validate(displayCall("demo_tap", displayId = 7.0, epoch = 2L,
                extras = mapOf("x" to 1, "y" to 2)))
        }
        validation("WRONG_ARGUMENT_TYPE") {
            ToolSchema.validate(displayCall("demo_tap", displayId = 7, epoch = 2,
                extras = mapOf("x" to 1, "y" to 2)))
        }
        validation("WRONG_ARGUMENT_TYPE") {
            ToolSchema.validate(displayCall("demo_tap", displayId = 7, epoch = 2L,
                extras = mapOf("x" to 1L, "y" to 2)))
        }
    }

    @Test
    fun inputTextCoordinatesAndTotalSizeAreBounded() {
        validation("INVALID_TAP") {
            ToolSchema.validate(displayCall("demo_tap", 7, 2L,
                mapOf("x" to 720, "y" to 2)))
        }
        validation("INVALID_TEXT") {
            ToolSchema.validate(displayCall("demo_text", 7, 2L,
                mapOf("text" to "line\nbreak")))
        }
        validation("STRING_TOO_LONG") {
            ToolSchema.validate(displayCall("demo_text", 7, 2L,
                mapOf("text" to "x".repeat(65))))
        }
        validation("INPUT_TOO_LARGE") {
            ToolSchema.validate(RawToolCall("android.device_info",
                mapOf("x" to "z".repeat(ToolSchema.MAX_INPUT_BYTES + 1))))
        }
    }

    @Test
    fun fixedCanonicalCsvIsAccepted() {
        assertEquals(root.resolve("items.csv"),
            CanonicalChildPath.existingRegularFile(root, CanonicalChildPath.DEMO_RELATIVE_PATH))
    }

    @Test
    fun traversalAbsoluteAndDotSegmentsAreRejected() {
        validation("PATH_TRAVERSAL") { CanonicalChildPath.existingRegularFile(root, "../items.csv") }
        validation("PATH_TRAVERSAL") { CanonicalChildPath.existingRegularFile(root, "/workspace/items.csv") }
        validation("PATH_TRAVERSAL") { CanonicalChildPath.existingRegularFile(root, "./items.csv") }
    }

    @Test
    fun symlinkChildIsRejectedEvenWhenItResolvesInsideRoot() {
        val link = root.resolve("linked.csv")
        Files.createSymbolicLink(link, root.resolve("items.csv"))
        validation("SYMLINK_REJECTED") { CanonicalChildPath.existingRegularFile(root, "linked.csv") }
    }

    @Test
    fun toolResultDefensivelyCopiesAndExposesAnUnmodifiableMap() {
        val source = linkedMapOf("value" to "first")
        val result = ToolResult(source)
        source["value"] = "changed"
        assertEquals("first", result.fields["value"])
        try {
            @Suppress("UNCHECKED_CAST")
            (result.fields as MutableMap<String, String>)["value"] = "mutated"
            fail("Expected unmodifiable result fields")
        } catch (_: UnsupportedOperationException) {
            assertEquals("first", result.fields["value"])
        }
    }

    @Test
    fun requestHashUsesUnambiguousLengthFraming() {
        val first = RequestHasher.hash(listOf("a" to "bc", "d" to ""))
        val second = RequestHasher.hash(listOf("a" to "b", "cd" to ""))
        assertEquals(64, first.length)
        assertTrue(first != second)
        assertEquals(first, RequestHasher.hash(listOf("a" to "bc", "d" to "")))
    }

    private fun displayCall(action: String, displayId: Any, epoch: Any,
        extras: Map<String, Any?> = emptyMap()): RawToolCall = RawToolCall(
        "android.virtual_display_observe_or_demo_input",
        linkedMapOf<String, Any?>(
            "action" to action,
            "expectedDisplayId" to displayId,
            "expectedDisplayEpoch" to epoch,
        ).apply { putAll(extras) },
    )

    private fun validation(code: String, block: () -> Unit) {
        try {
            block()
            fail("Expected ToolValidationException($code)")
        } catch (error: ToolValidationException) {
            assertEquals(code, error.code)
        }
    }
}
