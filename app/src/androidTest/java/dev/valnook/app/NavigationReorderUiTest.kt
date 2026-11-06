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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The navigation settings page is a draft from the start; only the toolbar's save writes it. */
@HiltAndroidTest
class NavigationReorderUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    private fun openPage() {
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        val navigationSettings = rule.activity.getString(dev.valnook.feature.settings.R.string.settings_navigation)
        rule.onNodeWithText(navigationSettings).performScrollTo().performClick()
        rule.onNodeWithTag("navigation-row-statistics").assertExists()
    }

    private fun stored(): NavigationConfiguration = runBlocking {
        rule.activity.sessions.session.value.graph.settings.observeSettings().first().navigation
    }

    private fun save() {
        rule.onNodeWithTag("navigation-save").assertIsEnabled().performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("navigation-save").fetchSemanticsNodes().single()
                .config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
    }

    @Test fun draggingPastTheAdjacentRowCenterMovesExactlyOnePosition() {
        openPage()
        rule.onNodeWithTag("navigation-save").assertIsNotEnabled()

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
        val toolbar = rule.onNodeWithTag("root-toolbar").fetchSemanticsNode().boundsInRoot
        assertTrue(toolbar.contains(rule.onNodeWithTag("navigation-save").fetchSemanticsNode().boundsInRoot.center))
        save()
        assertEquals(expected, stored().order)
    }

    @Test fun hiddenPagesMoveToTheirOwnGroupAndComeBackAtTheEnd() {
        openPage()
        val page = NavigationItemId.INVESTMENTS
        rule.onNodeWithTag("navigation-visible-${page.name.lowercase()}").performClick()
        rule.waitForIdle()
        // The page now sits below every page left on the bar.
        val shown = NavigationItemId.entries - page
        assertTrue(shown.all { rowCenterY(it) < rowCenterY(page) })
        save()
        assertFalse(page in stored().visible)

        rule.onNodeWithTag("navigation-visible-${page.name.lowercase()}").performClick()
        rule.waitForIdle()
        save()
        val navigation = stored()
        assertTrue(page in navigation.visible)
        assertEquals(page, navigation.visibleInOrder.last())
    }

    private fun rowsInVisualOrder(): List<NavigationItemId> = NavigationItemId.entries.sortedBy(::rowCenterY)

    private fun rowCenterY(item: NavigationItemId): Float = rule
        .onNodeWithTag("navigation-row-${item.name.lowercase()}")
        .fetchSemanticsNode().boundsInRoot.center.y
}
