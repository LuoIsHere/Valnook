package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.CompactEditButton
import dev.valnook.designsystem.SectionHeader
import dev.valnook.designsystem.ValnookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The compact edit button and a section header's link draw at 32dp but take touches over the 48dp minimum: Compose
 * gives a smaller pointer target the touches near it that hit nothing else.
 */
class CompactTouchTargetUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var clicks = 0

    private fun px(dp: Float) = dp * rule.density.density

    /** Taps [outside] dp above the node's drawn top edge, at its horizontal centre. */
    private fun tapAbove(tag: String, outside: Float) {
        val bounds = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(Offset(bounds.center.x, bounds.top - px(outside))) }
        rule.waitForIdle()
    }

    private fun assertTouchAtLeast48(tag: String) {
        val node = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(px(32f), node.boundsInRoot.height, 1f)
        assertTrue("${node.touchBoundsInRoot}", node.touchBoundsInRoot.height >= px(48f) - 1f)
        assertTrue("${node.touchBoundsInRoot}", node.touchBoundsInRoot.width >= px(48f) - 1f)
    }

    @Test fun the_compact_edit_button_takes_touches_over_48dp() {
        rule.setContent { ValnookTheme { Box(Modifier.padding(40.dp).testTag("edit")) { CompactEditButton("编辑") { clicks++ } } } }
        // The tag sits on the box around the button; the button itself is its only child.
        val button = rule.onNodeWithContentDescription("编辑").fetchSemanticsNode()
        assertEquals(px(32f), button.boundsInRoot.height, 1f)
        assertTrue("${button.touchBoundsInRoot}", button.touchBoundsInRoot.height >= px(48f) - 1f)
        val top = button.boundsInRoot.top
        rule.onRoot().performTouchInput { click(Offset(button.boundsInRoot.center.x, top - px(6f))) }
        rule.waitForIdle()
        assertEquals("6dp outside the drawn edge still taps it", 1, clicks)
        rule.onRoot().performTouchInput { click(Offset(button.boundsInRoot.center.x, top - px(12f))) }
        rule.waitForIdle()
        assertEquals("Past the 48dp target it does not", 1, clicks)
    }

    @Test fun a_section_headers_link_takes_touches_over_48dp() {
        rule.setContent { ValnookTheme { Box(Modifier.padding(40.dp).width(320.dp)) {
            SectionHeader("当前存单", "已结算", { clicks++ }, actionModifier = Modifier.testTag("link"))
        } } }
        assertTouchAtLeast48("link")
        tapAbove("link", 6f)
        assertEquals("6dp outside the drawn edge still taps it", 1, clicks)
        tapAbove("link", 12f)
        assertEquals("Past the 48dp target it does not", 1, clicks)
    }
}
