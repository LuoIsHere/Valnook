package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
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
        rule.onNodeWithTag("statistics-investment_value").assertExists()
        val chart = rule.onNodeWithTag("statistics-chart-total_assets")
        val selectionBeforeScroll = chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        chart.performTouchInput { swipeUp() }
        rule.waitForIdle()
        assertEquals(selectionBeforeScroll,
            rule.onNodeWithTag("statistics-chart-total_assets").fetchSemanticsNode()
                .config[SemanticsProperties.StateDescription])

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
}
