package dev.valnook.designsystem

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Adds or removes the leading minus and keeps the cursor or selection on the same digits. */
fun toggleSign(value: TextFieldValue): TextFieldValue {
    val text = when {
        value.text.startsWith("-") -> value.text.removePrefix("-")
        value.text.isBlank() -> "-"
        else -> "-${value.text}"
    }
    val shift = if (value.text.startsWith("-")) -1 else 1
    fun moved(index: Int) = (index + shift).coerceIn(0, text.length)
    return TextFieldValue(text, TextRange(moved(value.selection.start), moved(value.selection.end)))
}
