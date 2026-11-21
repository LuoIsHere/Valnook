package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.feature.accounts.AccountEditScreen
import dev.valnook.feature.accounts.AccountEditViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import javax.inject.Inject

/** Edge-to-edge windows are not resized for the IME, so focused inputs must be kept above it by the app. */
@HiltAndroidTest
class KeyboardInsetsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    private val graph get() = sessions.session.value.graph
    private val keyboard = ScreenKeyboard { rule.activity.window.decorView }

    @Before fun prepare() {
        keyboard.prepare()
        hilt.inject()
    }

    @After fun restore() = keyboard.restore()

    @Test fun exchange_rate_fields_and_save_stay_above_the_keyboard() {
        val sources = Currency.supported.filter { it.code != "CNY" }.take(10)
        runBlocking {
            val current = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveFinancialSettings(current.revision, Currency.of("CNY"),
                sources.map { FxRate(it, Currency.of("CNY"), BigDecimal("1.5")) }))
        }
        openSettings()
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_exchange_rates)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fx-rate-0").fetchSemanticsNodes().isNotEmpty() }
        val last = rule.onNodeWithTag("fx-rate-${sources.lastIndex}")
        last.performScrollTo().performClick()
        keyboard.show(rule)
        keyboard.assertAbove(rule, "fx-last-rate", last)
        // Saving is the ✓ in the toolbar: with the keyboard open it is still there to press, without scrolling.
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-${sources.lastIndex}"))).performTextInput("5")
        keyboard.assertAbove(rule, "fx-save", rule.onNodeWithTag("fx-save").assertIsEnabled())
    }

    @Test fun clear_data_input_and_confirm_stay_above_the_keyboard() {
        openSettings()
        // The settings list is lazy and the danger zone sits below the fold.
        val clear = text(dev.valnook.feature.settings.R.string.settings_clear_data)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(clear))
        rule.onNodeWithText(clear).performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val input = rule.onNode(hasSetTextAction())
        input.performClick()
        keyboard.show(rule)
        keyboard.assertAbove(rule, "clear-input", input)
        val confirm = rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_confirm_clear))
        confirm.performScrollTo()
        keyboard.assertAbove(rule, "clear-confirm", confirm)
    }

    /** A sheet is a window of its own, which the page's keyboard padding does not reach. */
    @Test fun subaccount_panel_keeps_its_last_field_above_the_keyboard() {
        val vm = rule.runOnUiThread { AccountEditViewModel(null, graph.overview, graph.commands, SavedStateHandle()) }
        rule.waitUntil(10_000) { vm.state.value.loaded }
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme { AccountEditScreen(vm, {}) } } }
        rule.onNodeWithTag("account-add-row").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("subaccount-sheet").fetchSemanticsNodes().isNotEmpty() }
        // A credit card has the most fields, so its last one sits lowest in the panel.
        val key = vm.state.value.rows.single().key
        rule.runOnUiThread { vm.changeRow(key, type = BalanceAccountType.CREDIT) }
        rule.waitForIdle()
        val fields = hasSetTextAction() and hasAnyAncestor(hasTestTag("subaccount-sheet"))
        val last = rule.onAllNodes(fields)[rule.onAllNodes(fields).fetchSemanticsNodes().lastIndex]
        last.performScrollTo().performClick()
        keyboard.show(rule)
        keyboard.assertAbove(rule, "subaccount-last-field", last)
    }

    private fun openSettings() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun text(id: Int) = rule.activity.getString(id)
}
