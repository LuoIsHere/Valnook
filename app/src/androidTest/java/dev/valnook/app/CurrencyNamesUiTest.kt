package dev.valnook.app

import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.valnook.designsystem.CurrencyChoice
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.designsystem.interfaceLanguage
import dev.valnook.domain.model.Currency
import java.util.Locale
import org.junit.Rule
import org.junit.Test

/** The currency picker names currencies in the interface language, and searches those names. */
class CurrencyNamesUiTest {
    @get:Rule val rule = createComposeRule()

    private fun open(locale: Locale) {
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                ValnookTheme { CurrencyChoice("CNY", {}, true, Currency.options(interfaceLanguage())) }
            }
        }
        rule.onNodeWithContentDescription("CNY", substring = true).performClick()
    }

    @Test fun english_interface_shows_and_searches_english_names() {
        open(Locale.ENGLISH)
        rule.onNodeWithText("Euro").assertExists()
        rule.onNodeWithText("欧元").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("euro")
        rule.onNodeWithText("Euro").assertExists()
        rule.onNodeWithText("US Dollar").assertDoesNotExist()
    }

    @Test fun chinese_interface_shows_and_searches_chinese_names() {
        open(Locale.SIMPLIFIED_CHINESE)
        rule.onNodeWithText("欧元").assertExists()
        rule.onNodeWithText("Euro").assertDoesNotExist()
        rule.onNode(hasSetTextAction()).performTextInput("欧")
        rule.onNodeWithText("欧元").assertExists()
        rule.onNodeWithText("美元").assertDoesNotExist()
    }
}
