package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The grouped form rows and filled fields used by the account edit page. */
class FormRowsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun scene(content: @Composable () -> Unit) {
        rule.setContent { ValnookTheme { Box { Column(Modifier.width(360.dp)) { content() } } } }
    }

    @Test fun text_row_takes_typing_under_its_label_and_shows_its_error() {
        var value by mutableStateOf("Bank")
        var error by mutableStateOf<String?>(null)
        scene { FormSection("Group") { FormTextRow("Name", value, { value = it }, error = error) } }
        rule.onNodeWithText("Name").performTextReplacement("Savings bank")
        assertEquals("Savings bank", value)
        rule.onNodeWithTag("input-Name").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        error = "NAME"
        rule.onNodeWithTag("input-Name").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
    }

    @Test fun filled_field_flips_the_sign_and_choice_reads_label_and_value() {
        var amount by mutableStateOf("12.50")
        var opened = 0
        scene {
            FilledField("Balance", amount, { amount = it }, numeric = true, signed = true)
            FilledChoiceField("Currency", "CNY", { opened++ })
            FilledChoiceField("Locked", "USD", { opened++ }, enabled = false)
        }
        rule.onNodeWithContentDescription(rule.activity.getString(dev.valnook.core.designsystem.R.string.toggle_sign)).performClick()
        assertEquals("-12.50", amount)
        rule.onNodeWithContentDescription("Currency: CNY").assertIsEnabled().performClick()
        rule.onNodeWithContentDescription("Locked: USD").assertIsNotEnabled()
        assertEquals(1, opened)
    }

    @Test fun switch_row_toggles_from_anywhere_on_the_line() {
        var on by mutableStateOf(false)
        scene {
            FormSwitchRow("Show deposits", on, { on = it })
            FormSwitchRow("Locked switch", true, { on = !on }, enabled = false)
        }
        rule.onNodeWithText("Show deposits").performClick()
        rule.onNodeWithTag("switch-Show deposits").assertIsOn()
        rule.onNodeWithTag("switch-Locked switch").assertIsNotEnabled().performClick()
        assertEquals(true, on)
    }

    @Test fun disabled_capsule_keeps_its_choice() {
        var choice by mutableStateOf("A")
        scene { CapsuleChoiceRow(listOf("A", "B"), choice, { choice = it }, enabled = false) { Text(it) } }
        rule.onNode(hasText("B") and isSelectable()).assertIsNotEnabled().performClick()
        assertEquals("A", choice)
    }
}
