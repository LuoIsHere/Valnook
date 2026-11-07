package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.FaIcons
import dev.valnook.designsystem.NavigationCapsule
import dev.valnook.designsystem.ValnookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The navigation capsule folds to one tab holding the chosen page, and then only opens again when tapped. */
class NavigationCapsuleCollapseUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var pages by mutableStateOf(listOf("A", "B", "C", "D", "E"))
    private var chosen by mutableStateOf("C")
    private var collapse by mutableFloatStateOf(0f)
    private var expanded = 0

    /** Set by [scene]: px per dp. A wide host is drawn at a low density so all of it stays on the screen. */
    private var density = 0f

    private fun scene(direction: LayoutDirection = LayoutDirection.Ltr, width: Int = 360, density: Float? = null) {
        this.density = density ?: rule.density.density
        rule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalLayoutDirection provides direction,
                LocalDensity provides Density(density ?: current.density, current.fontScale)) {
                ValnookTheme { Box { Box(Modifier.requiredWidth(width.dp).testTag("host")) {
                    NavigationCapsule(pages, chosen, { chosen = it }, { FaIcons.gear }, { it },
                        optionModifier = { Modifier.testTag("tab-$it") },
                        collapse = collapse, onExpand = { expanded++ }, expandLabel = "Current page: $chosen, tap to expand")
                } } }
            }
        }
        rule.waitForIdle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun the_whole_bar_chooses_pages() {
        scene()
        assertEquals(bounds("host").width, bounds("navigation-capsule").width, 1f)
        rule.onNodeWithTag("navigation-expand").assertDoesNotExist()
        rule.onNodeWithTag("tab-E").performClick()
        assertEquals("E", chosen)
    }

    @Test fun folded_it_is_one_tab_at_the_start_that_only_expands() {
        scene()
        val tab = bounds("tab-A").width
        collapse = 1f
        rule.waitForIdle()
        val capsule = bounds("navigation-capsule")
        assertEquals(bounds("host").left, capsule.left, 1f)
        // One tab plus the capsule's padding on both sides.
        assertEquals(tab + 8 * rule.density.density, capsule.width, 2f)
        val held = bounds("tab-C")
        assertTrue("The chosen page sits in the folded capsule", held.left >= capsule.left - 1 && held.right <= capsule.right + 1)
        rule.onNodeWithTag("navigation-tabs", useUnmergedTree = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        rule.onNodeWithContentDescription("Current page: C, tap to expand").assertHasClickAction().performClick()
        assertEquals(1, expanded)
        assertEquals("C", chosen)
    }

    @Test fun halfway_the_width_is_between_both_ends() {
        scene()
        val full = bounds("navigation-capsule").width
        collapse = 1f
        rule.waitForIdle()
        val folded = bounds("navigation-capsule").width
        collapse = .4f
        rule.waitForIdle()
        val middle = bounds("navigation-capsule").width
        assertTrue(middle < full && middle > folded)
        rule.onNodeWithTag("navigation-expand").assertDoesNotExist()
    }

    private fun px(dp: Float) = dp * density

    @Test fun fewer_pages_keep_the_tab_width_and_center_the_bar() {
        scene()
        val tab = bounds("tab-A").width
        pages = listOf("A", "B", "C")
        rule.waitForIdle()
        val capsule = bounds("navigation-capsule")
        assertEquals(tab, bounds("tab-A").width, 1f)
        assertEquals(tab, bounds("tab-C").width, 1f)
        // A tab is a fifth of the host less the padding and four gaps; three of them with two gaps and the padding.
        val fifth = (bounds("host").width - px(4f * 2 + 4f * 4)) / 5
        assertEquals("Three tabs, their gaps and the padding", 3 * fifth + px(4f * 2 + 4f * 2), capsule.width, 2f)
        assertEquals("Centered", bounds("host").center.x, capsule.center.x, 1f)
        rule.onNodeWithTag("tab-B").performClick()
        assertEquals("B", chosen)
    }

    @Test fun folded_it_takes_the_same_place_and_size_for_any_number_of_pages() {
        scene()
        collapse = 1f
        rule.waitForIdle()
        val five = bounds("navigation-capsule")
        pages = listOf("A", "C")
        rule.waitForIdle()
        val two = bounds("navigation-capsule")
        assertEquals(five.left, two.left, 1f)
        assertEquals(five.width, two.width, 1f)
        assertEquals(bounds("host").left, two.left, 1f)
    }

    @Test fun a_wide_screen_stops_each_tab_at_its_largest_and_centers_the_bar() {
        scene(width = 800, density = 1f)
        val capsule = bounds("navigation-capsule")
        assertEquals(px(96f), bounds("tab-A").width, 1f)
        assertEquals(px(5 * 96f + 4 * 4f + 8f), capsule.width, 2f)
        assertEquals(bounds("host").center.x, capsule.center.x, 1f)
    }

    @Test fun right_to_left_folds_towards_the_right() {
        scene(LayoutDirection.Rtl)
        collapse = 1f
        rule.waitForIdle()
        val capsule = bounds("navigation-capsule")
        assertEquals(bounds("host").right, capsule.right, 1f)
        val held = bounds("tab-C")
        assertTrue(held.left >= capsule.left - 1 && held.right <= capsule.right + 1)
    }
}
