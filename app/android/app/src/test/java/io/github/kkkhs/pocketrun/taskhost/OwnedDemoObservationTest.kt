package io.github.kkkhs.pocketrun.taskhost

import io.github.kkkhs.pocketrun.UiTreeCodec
import org.junit.Assert.*
import org.junit.Test

class OwnedDemoObservationTest {
    private val packageName = "io.github.kkkhs.pocketrun"
    private val target = DisplayTarget(3, 7, 10001, true)
    private val input = UiTreeCodec.Node(-1, 9, 0, "android.widget.EditText", packageName,
        "android:id/edit", "", "", 10, 20, 200, 80, true, true, true, false)
    private val result = input.copy(className = "android.widget.TextView", viewId = "android:id/text1",
        text = "input: ", editable = false, clickable = false, top = 90, bottom = 130)
    private val tree = UiTreeCodec.Tree(target.displayId, UiTreeCodec.Status.Available, false, listOf(input, result))

    @Test fun exactTwoViewsProvideBoundedValuesBoundsFocusAndNoOtherUiFields() {
        val value = OwnedDemoObservation.select(tree, target, packageName)
        assertEquals("", value.value)
        assertEquals("input: ", value.resultValue)
        assertEquals(105, value.x)
        assertEquals(50, value.y)
        assertFalse(value.focused)
        val fields = OwnedDemoObservation.fields(value)
        assertEquals(setOf("source", "displayId", "displayEpoch", "demoValue", "demoResult",
            "left", "top", "right", "bottom", "focused", "x", "y"), fields.keys)
        assertEquals("app-owned-demo-typed-binder", fields["source"])
        assertEquals("7", fields["displayId"])
        assertEquals("3", fields["displayEpoch"])
        val report = "items=2;quantity=5"
        val filled = tree.copy(nodes = listOf(input.copy(text = report, focused = true), result.copy(text = "input: $report")))
        assertEquals(report, OwnedDemoObservation.select(filled, target, packageName).value)
        assertTrue(OwnedDemoObservation.select(filled, target, packageName).focused)
    }

    @Test fun hintAloneNeverProvesTheInputIsEmpty() {
        val hint = input.copy(text = "PocketRun_Ditto_5")
        val empty = OwnedDemoObservation.select(tree.copy(nodes = listOf(hint, result)), target, packageName)
        assertEquals("", empty.value)
        val actuallyTyped = tree.copy(nodes = listOf(hint, result.copy(text = "input: PocketRun_Ditto_5")))
        assertEquals("PocketRun_Ditto_5", OwnedDemoObservation.select(actuallyTyped, target, packageName).value)
        assertThrows(IllegalArgumentException::class.java) {
            OwnedDemoObservation.select(tree.copy(nodes = listOf(hint, result.copy(text = "unknown"))), target, packageName)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OwnedDemoObservation.select(tree.copy(nodes = listOf(input.copy(text = "items=2"), result)), target, packageName)
        }
        listOf("界", "x".repeat(65)).forEach { text ->
            val unsupported = tree.copy(nodes = listOf(input.copy(text = text), result.copy(text = "input: $text")))
            assertThrows(IllegalArgumentException::class.java) { OwnedDemoObservation.select(unsupported, target, packageName) }
        }
    }

    @Test fun unavailableTruncatedWrongDisplayAndNonOwnedOrDuplicateViewsAreRejected() {
        val unavailable = listOf(tree.copy(truncated = true), tree.copy(displayId = 8),
            tree.copy(status = UiTreeCodec.Status.Timeout, nodes = emptyList()),
            tree.copy(nodes = listOf(input.copy(packageName = "other.app"), result)),
            tree.copy(nodes = listOf(input, result.copy(packageName = "other.app"))),
            tree.copy(nodes = listOf(input, input, result)), tree.copy(nodes = listOf(input, result, result)))
        unavailable.forEach {
            assertThrows(IllegalArgumentException::class.java) { OwnedDemoObservation.select(it, target, packageName) }
        }
        listOf(target.copy(backendReady = false), target.copy(displayId = 0), target.copy(displayEpoch = 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { OwnedDemoObservation.select(tree, it, packageName) }
        }
        // A matching ID in another App is neither a replacement nor a duplicate of our view.
        val other = input.copy(packageName = "other.app", text = "private text")
        assertEquals("", OwnedDemoObservation.select(tree.copy(nodes = tree.nodes + other), target, packageName).value)
    }

    @Test fun invalidInputClassWindowEditabilityAndBoundsCannotBecomeTapTargets() {
        val invalid = listOf(input.copy(className = "android.widget.TextView"), input.copy(editable = false),
            input.copy(enabled = false), input.copy(windowId = 10), input.copy(left = -1),
            input.copy(right = 721), input.copy(bottom = 1281), input.copy(right = input.left),
            input.copy(bottom = input.top))
        invalid.forEach {
            assertThrows(IllegalArgumentException::class.java) {
                OwnedDemoObservation.select(tree.copy(nodes = listOf(it, result)), target, packageName)
            }
        }
        listOf(result.copy(className = "android.widget.EditText"), result.copy(enabled = false)).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                OwnedDemoObservation.select(tree.copy(nodes = listOf(input, it)), target, packageName)
            }
        }
    }
}
