package dev.valnook.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.CurrencyChoice
import dev.valnook.designsystem.CurrencyPickerRates
import dev.valnook.designsystem.LocalCurrencyPickerRates
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.Currency
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A rate fetched with twelve decimal places stays on one line in the currency picker. */
class CurrencyPickerRateUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun a_long_fetched_rate_is_shortened_to_one_line() {
        val rates = CurrencyPickerRates("CNY", mapOf("GBP" to "8.958967926895", "USD" to "6.66"))
        rule.setContent {
            ValnookTheme {
                CompositionLocalProvider(LocalCurrencyPickerRates provides rates) {
                    Box(Modifier.width(360.dp)) { CurrencyChoice("USD", {}, options = Currency.options("zh")) }
                }
            }
        }
        rule.onNodeWithContentDescription("币种: USD").performClick()
        val gbp = rule.onNodeWithTag("currency-rate-GBP", useUnmergedTree = true).assertTextEquals("8.95897")
        val usd = rule.onNodeWithTag("currency-rate-USD", useUnmergedTree = true).assertTextEquals("6.66")
        assertEquals(usd.fetchSemanticsNode().size.height, gbp.fetchSemanticsNode().size.height)
    }
}
