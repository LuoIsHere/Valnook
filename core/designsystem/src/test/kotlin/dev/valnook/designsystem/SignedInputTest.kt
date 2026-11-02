package dev.valnook.designsystem

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class SignedInputTest {
    private fun toggled(text: String, start: Int, end: Int = start) =
        toggleSign(TextFieldValue(text, TextRange(start, end))).let { it.text to it.selection }

    @Test fun an_empty_field_gets_a_minus_with_the_cursor_after_it() {
        assertEquals("-" to TextRange(1), toggled("", 0))
    }

    @Test fun adding_the_minus_keeps_the_cursor_on_the_same_digit() {
        assertEquals("-100" to TextRange(1), toggled("100", 0))
        assertEquals("-100" to TextRange(2), toggled("100", 1))
        assertEquals("-100" to TextRange(4), toggled("100", 3))
    }

    @Test fun removing_the_minus_keeps_the_cursor_on_the_same_digit() {
        assertEquals("100" to TextRange(0), toggled("-100", 0))
        assertEquals("100" to TextRange(0), toggled("-100", 1))
        assertEquals("100" to TextRange(3), toggled("-100", 4))
    }

    @Test fun a_selection_moves_with_the_digits() {
        assertEquals("-100" to TextRange(2, 4), toggled("100", 1, 3))
        assertEquals("100" to TextRange(0, 2), toggled("-100", 0, 3))
    }
}
