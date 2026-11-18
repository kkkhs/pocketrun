package io.github.kkkhs.pocketrun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayInputPolicyTest {
    @Test fun acceptsOnlyTheCurrentNonDefaultDisplay() {
        DisplayInputPolicy.requireDisplay(7, 7)
        for (invalid in listOf(-1, 0, Int.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                DisplayInputPolicy.requireDisplay(invalid, 7)
            }
        }
        for (current in listOf(-1, 0, 8)) {
            assertThrows(IllegalStateException::class.java) {
                DisplayInputPolicy.requireDisplay(7, current)
            }
        }
    }

    @Test fun rejectsReleasedAndStaleDisplayAfterRecreation() {
        DisplayInputPolicy.requireDisplay(7, 7)
        assertThrows(IllegalStateException::class.java) { DisplayInputPolicy.requireDisplay(7, -1) }
        assertThrows(IllegalStateException::class.java) { DisplayInputPolicy.requireDisplay(7, 9) }
        DisplayInputPolicy.requireDisplay(9, 9)
    }

    @Test fun tapUsesExclusivePixelBounds() {
        assertTrue(DisplayInputPolicy.acceptsTap(0, 0))
        assertTrue(DisplayInputPolicy.acceptsTap(719, 1279))
        assertFalse(DisplayInputPolicy.acceptsTap(-1, 0))
        assertFalse(DisplayInputPolicy.acceptsTap(0, -1))
        assertFalse(DisplayInputPolicy.acceptsTap(720, 0))
        assertFalse(DisplayInputPolicy.acceptsTap(0, 1280))
        assertFalse(DisplayInputPolicy.acceptsTap(Int.MAX_VALUE, Int.MIN_VALUE))
    }

    @Test fun textAllowsOnlyBoundedPrintableAscii() {
        assertTrue(DisplayInputPolicy.acceptsText("PocketRun_Ditto_5"))
        assertTrue(DisplayInputPolicy.acceptsText(" ".repeat(64)))
        assertFalse(DisplayInputPolicy.acceptsText(""))
        assertFalse(DisplayInputPolicy.acceptsText("a".repeat(65)))
        for (code in 0x20..0x7e) assertTrue(DisplayInputPolicy.acceptsText(code.toChar().toString()))
        for (text in listOf("\u001f", "\u007f", "\n", "\t", "中文", "é", "😀", "\uD800")) {
            assertFalse(DisplayInputPolicy.acceptsText(text))
        }
    }
}
