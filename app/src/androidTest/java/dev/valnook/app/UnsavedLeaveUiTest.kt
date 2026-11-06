package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.repository.SaveFinancialSettings
import java.math.BigDecimal
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The navigation and rates pages save from the toolbar, so leaving them with changes asks first. */
@HiltAndroidTest
class UnsavedLeaveUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val graph get() = rule.activity.sessions.session.value.graph
    private fun text(id: Int) = rule.activity.getString(id)
    private fun settings() = runBlocking { graph.settings.observeSettings().first() }

    private fun openSettings() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openNavigationPage() {
        openSettings()
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_navigation)).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("navigation-row-statistics").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun openRatesPage() {
        runBlocking {
            graph.settingsWriter.applyChange(SaveFinancialSettings(settings().revision, Currency.of("CNY"),
                listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("6.66")))))
        }
        openSettings()
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_exchange_rates)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fx-rate-0").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun toolbarBack() = rule.onNodeWithContentDescription(text(R.string.nav_back)).performClick()
    private fun systemBack() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; rule.waitForIdle() }
    private fun prompt() = rule.onAllNodesWithTag("leave-discard").fetchSemanticsNodes().isNotEmpty()
    private fun onSettingsPage() = rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty()

    @Test fun navigation_page_asks_keeps_editing_then_discards() {
        openNavigationPage()
        val before = settings().navigation
        rule.onNodeWithTag("navigation-visible-statistics").performClick()
        rule.onNodeWithTag("navigation-save").assertIsEnabled()

        toolbarBack()
        rule.waitUntil(5_000) { prompt() }
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_leave_body)).assertExists()
        rule.onNodeWithTag("leave-keep").performClick()
        rule.waitUntil(5_000) { !prompt() }
        rule.onNodeWithTag("navigation-save").assertIsEnabled()   // still here, change kept

        systemBack()
        rule.waitUntil(5_000) { prompt() }
        rule.onNodeWithTag("leave-discard").performClick()
        rule.waitUntil(10_000) { onSettingsPage() && !prompt() }
        assertEquals(before, settings().navigation)

        // The draft went with the page: opening it again starts from what is stored.
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_navigation)).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("navigation-row-statistics").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("navigation-save").assertIsNotEnabled()
    }

    @Test fun navigation_page_without_changes_leaves_at_once() {
        openNavigationPage()
        toolbarBack()
        rule.waitUntil(10_000) { onSettingsPage() }
        assertTrue(!prompt())
    }

    @Test fun rates_page_asks_on_leaving_and_not_after_saving() {
        openRatesPage()
        val field = hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-0"))
        rule.onNode(field).performTextReplacement("7.1")
        // The page hands its changed state to the toolbar one frame later; a user cannot press back sooner.
        rule.onNodeWithTag("fx-save").assertIsEnabled()
        systemBack()
        rule.waitUntil(5_000) { prompt() }
        rule.onNodeWithTag("leave-discard").performClick()
        rule.waitUntil(10_000) { onSettingsPage() && !prompt() }
        assertEquals(BigDecimal("6.66"), settings().rates.single().rate)

        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_exchange_rates)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fx-rate-0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(field).assert(hasText("6.66"))
        rule.onNode(field).performTextReplacement("7.2")
        rule.onNodeWithTag("fx-save").performClick()
        rule.waitUntil(10_000) { settings().rates.single().rate == BigDecimal("7.2") }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("fx-save").fetchSemanticsNodes().single()
                .config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        toolbarBack()
        rule.waitUntil(10_000) { onSettingsPage() }
        assertTrue(!prompt())
    }
}
