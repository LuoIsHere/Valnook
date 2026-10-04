package dev.valnook.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.domain.model.NavigationItemId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class NavigationReorderUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun draggingPastTheAdjacentRowCenterMovesExactlyOnePosition() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        val navigationSettings = rule.activity.getString(dev.valnook.feature.settings.R.string.settings_navigation)
        rule.onNodeWithText(navigationSettings).performScrollTo().performClick()
        rule.onNodeWithTag("navigation-row-statistics").assertExists()
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.settings.R.string.settings_edit))
            .performClick()

        val initialOrder = rowsInVisualOrder()
        val dragged = initialOrder[0]
        val adjacent = initialOrder[1]
        val rowDistance = rowCenterY(adjacent) - rowCenterY(dragged)

        rule.onNodeWithTag("navigation-reorder-${dragged.name.lowercase()}").performTouchInput {
            swipe(center, center + Offset(0f, rowDistance * 1.15f), durationMillis = 400)
        }
        rule.waitForIdle()

        val expected = initialOrder.toMutableList().apply {
            this[0] = adjacent
            this[1] = dragged
        }
        assertEquals(expected, rowsInVisualOrder())
    }

    private fun rowsInVisualOrder(): List<NavigationItemId> = NavigationItemId.entries.sortedBy(::rowCenterY)

    private fun rowCenterY(item: NavigationItemId): Float = rule
        .onNodeWithTag("navigation-row-${item.name.lowercase()}")
        .fetchSemanticsNode().boundsInRoot.center.y
}
