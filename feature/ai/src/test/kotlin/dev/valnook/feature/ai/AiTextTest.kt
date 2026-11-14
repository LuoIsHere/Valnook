package dev.valnook.feature.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Replies are plain text; stray Markdown only gets its bold and its list markers read. */
class AiTextTest {
    @Test fun bold_and_list_markers_are_read_and_the_rest_stays() {
        val (text, bold) = AiText.parse("Saved **two** bills:\n- lunch 25\n  * taxi 32\n# not a heading")
        assertEquals("Saved two bills:\n• lunch 25\n  • taxi 32\n# not a heading", text)
        assertEquals(listOf(6..8), bold)
    }

    @Test fun plain_text_is_left_alone() {
        assertEquals("3*4 = 12, a-b" to emptyList<IntRange>(), AiText.parse("3*4 = 12, a-b"))
    }
}
