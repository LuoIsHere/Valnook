package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class StatisticsNavigationUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun defaultNavigationOpensThreeIndependentChartsAndSettingsEditor() {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("nav-accounts").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("nav-accounts").assertExists()
        rule.onNodeWithTag("nav-investments").assertExists()
        rule.onNodeWithTag("nav-statistics").assertExists().performClick()
        try {
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("statistics-total_assets").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (error: Throwable) {
            throw AssertionError(rule.onRoot().printToString(maxDepth = 6), error)
        }
        rule.onNodeWithTag("statistics-total_assets").assertExists()
        rule.onNodeWithTag("statistics-available_cash").assertExists()
        val daily = rule.onNodeWithTag("statistics-granularity-total_assets-daily")
        val monthly = rule.onNodeWithTag("statistics-granularity-total_assets-monthly")
        daily.assertIsSelected()
        monthly.assertIsNotSelected().performClick()
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("statistics-granularity-total_assets-monthly")
                .fetchSemanticsNode().config[SemanticsProperties.Selected]
        }
        monthly.assertIsSelected()
        daily.performClick()
        rule.waitUntil(5_000) {
            rule.onNodeWithTag("statistics-granularity-total_assets-daily")
                .fetchSemanticsNode().config[SemanticsProperties.Selected]
        }
        assertTrue(rule.onNodeWithTag("statistics-monthly-change").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithTag("statistics-total_assets").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(rule.onNodeWithTag("statistics-readout-total_assets")
            .fetchSemanticsNode().boundsInRoot.height > 0f)
        val chart = rule.onNodeWithTag("statistics-chart-total_assets")
        val selectionBeforeScroll = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        chart.performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(selectionBeforeScroll,
            rule.onNodeWithTag("statistics-chart-total_assets").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription])
        chart.performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(selectionBeforeScroll,
            rule.onNodeWithTag("statistics-chart-total_assets").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription])

        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("statistics-investment_value"))
        rule.onNodeWithTag("statistics-investment_value").assertExists()
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(rule.activity.getString(dev.valnook.feature.settings.R.string.settings_navigation))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.settings.R.string.settings_navigation))
            .performScrollTo().performClick()
        rule.onNodeWithTag("navigation-row-statistics").assertExists()
        rule.onNodeWithTag("navigation-visible-settings").assertIsNotEnabled()
    }

    @Test fun backup_page_exposes_real_actions_and_demo_only_excel() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-backup-export").performScrollTo().performClick()
        rule.onNodeWithTag("backup-create").assertExists()
        rule.onNodeWithTag("backup-restore").assertExists()
        rule.onNodeWithTag("backup-export-excel").assertExists()
        rule.onNodeWithTag("cloud-connect").assertExists()
        rule.onNodeWithTag("cloud-title-icon", useUnmergedTree = true).assertExists()
        rule.activity.onBackPressedDispatcher.onBackPressed()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("settings-demo-switch").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-demo-switch").performScrollTo().performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(rule.activity.getString(dev.valnook.feature.settings.R.string.settings_demo_banner))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-backup-export").performScrollTo().performClick()
        rule.onNodeWithTag("backup-export-excel").assertExists()
        rule.onNodeWithTag("backup-create").assertDoesNotExist()
        rule.onNodeWithTag("backup-restore").assertDoesNotExist()
        rule.onNodeWithTag("cloud-connect").assertDoesNotExist()
    }
}
