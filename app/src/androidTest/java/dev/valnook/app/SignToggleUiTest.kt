package dev.valnook.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.designsystem.Field
import dev.valnook.designsystem.ValnookTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The shared signed amount field: ± leaves the cursor on the same digits, so typing goes where it should. */
class SignToggleUiTest {
    @get:Rule val rule = createComposeRule()
    private var value by mutableStateOf("")
    private val signLabel = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(dev.valnook.core.designsystem.R.string.toggle_sign)

    private fun show() {
        rule.setContent { ValnookTheme { Field("Balance", value, { value = it }, numeric = true, signed = true) } }
    }

    private fun input() = rule.onNode(hasSetTextAction())
    private fun toggle() { rule.onNodeWithContentDescription(signLabel).performClick() }

    @Test fun typing_after_the_sign_on_an_empty_field_stays_after_the_minus() {
        show()
        toggle()
        input().performTextInput("5")
        rule.runOnIdle { assertEquals("-5", value) }
    }

    @Test fun typing_after_the_sign_stays_at_the_end_of_the_amount() {
        show()
        input().performTextReplacement("43208.23")
        toggle()
        input().performTextInput("9")
        rule.runOnIdle { assertEquals("-43208.239", value) }
    }

    @Test fun removing_the_sign_keeps_the_cursor_at_the_end() {
        show()
        input().performTextReplacement("-100")
        toggle()
        input().performTextInput("5")
        rule.runOnIdle { assertEquals("1005", value) }
    }
}
