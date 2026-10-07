package dev.valnook.app

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.settings.*
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest

@HiltAndroidTest
class SettingsGlassUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createComposeRule()
    @Before fun prepare() { hilt.inject() }
    private class Store : SettingsRepository, SettingsWriter {
        val value = MutableStateFlow(AppSettings(baseCurrency = Currency.of("CNY")))
        var writes = 0
        var fail = false
        override fun observeSettings() = value
        override suspend fun applyChange(change: SettingsChange): AppSettings {
            if (fail) throw DomainException(ErrorCode.STALE_RECORD)
            check(change.expectedRevision == value.value.revision)
            writes++
            return when (change) {
                is SaveLanguage -> value.value.copy(language = change.language)
                is SaveGainLossColors -> value.value.copy(gainLossColors = change.colors)
                is SaveFinancialSettings -> value.value.copy(baseCurrency = change.baseCurrency, rates = change.rates)
                is SaveNavigationConfiguration -> value.value.copy(navigation = change.configuration)
            }.copy(revision = value.value.revision + 1).also { value.value = it }
        }
    }

    private fun home(store: Store, demo: Boolean = false, dark: Boolean = false) {
        val vm = SettingsViewModel(store, store, SavedStateHandle())
        rule.setContent { ValnookTheme(dark_theme = dark) {
            SettingsHome(vm, demo, false, false, {}, {}, {}, {}, {}, {}, {}, "0.0.9", "test")
        } }
    }

    private fun capture(tag: String, name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "ui-glass-test/$name.png")
        output.parentFile!!.mkdirs()
        val bitmap = rule.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun language_and_colors_share_single_choice_dialog_and_save_once() {
        val store = Store()
        home(store)
        rule.onNodeWithTag("settings-language").performScrollTo().performClick()
        rule.onNodeWithTag("language-option-SYSTEM").assertIsSelected()
        capture("language-picker", "language-light")
        rule.onNodeWithTag("language-option-ENGLISH").performClick()
        rule.onNodeWithTag("language-picker").assertDoesNotExist()
        assertEquals(AppLanguage.ENGLISH, store.value.value.language)
        assertEquals(1, store.writes)
        rule.onNodeWithTag("settings-colors").performScrollTo().performClick()
        capture("colors-picker", "colors-light")
        val choice = GainLossColorScheme.entries.first { it != store.value.value.gainLossColors }
        rule.onNodeWithTag("colors-option-${choice.name}").performClick()
        rule.onNodeWithTag("colors-picker").assertDoesNotExist()
        assertEquals(choice, store.value.value.gainLossColors)
        assertEquals(2, store.writes)
    }

    @Test fun failed_choice_stays_open_and_cancel_preserves_saved_value() {
        val store = Store().apply { fail = true }
        home(store, dark = true)
        rule.onNodeWithTag("settings-language").performScrollTo().performClick()
        rule.onNodeWithTag("language-option-ENGLISH").performClick()
        rule.onNodeWithTag("language-picker").assertIsDisplayed()
        rule.onNodeWithTag("language-option-SYSTEM").assertIsSelected()
        capture("language-picker", "language-error-dark")
        rule.onNodeWithText(InstrumentationRegistry.getInstrumentation().targetContext
            .getString(dev.valnook.core.designsystem.R.string.cancel)).performClick()
        rule.onNodeWithTag("language-picker").assertDoesNotExist()
        assertEquals(AppLanguage.SYSTEM, store.value.value.language)
        assertEquals(0, store.writes)
    }

    @Test fun demo_hides_language_but_keeps_colors_popup() {
        home(Store(), demo = true)
        rule.onNodeWithTag("settings-language").assertDoesNotExist()
        rule.onNodeWithTag("settings-colors").performScrollTo().performClick()
        rule.onNodeWithTag("colors-picker").assertIsDisplayed()
    }

    @Test fun frosted_rate_input_edits_decimal_at_narrow_width_and_large_font() {
        val store = Store()
        val vm = SettingsViewModel(store, store, SavedStateHandle())
        rule.setContent {
            ValnookTheme(dark_theme = true) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
                    Box(Modifier.width(320.dp)) { FxSettingsScreen(vm) }
                }
            }
        }
        rule.runOnIdle { vm.addRate() }
        val label = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(dev.valnook.feature.settings.R.string.settings_rate)
        rule.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement("7.2345")
        rule.onNode(hasSetTextAction() and hasText(label)).performImeAction()
        rule.runOnIdle { assertEquals("7.2345", vm.state.value.rows.single().rateInput); vm.saveRates() }
        rule.waitForIdle()
        assertEquals("7.2345", store.value.value.rates.single().rate.toPlainString())
    }
}
