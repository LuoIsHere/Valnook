package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * Asserts that the scrolling area [matcher] finds runs down to the screen's bottom edge, behind the navigation bar,
 * instead of stopping above it. The bar must have a height, or the check would say nothing.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.assertReachesScreenBottom(name: String, matcher: SemanticsMatcher) {
    waitForIdle()
    val (screenBottom, navigationBar) = runOnUiThread {
        val view = activity.window.decorView
        val location = IntArray(2).also(view::getLocationOnScreen)
        val bar = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        location[1] + view.height to bar
    }
    assertTrue("$name: the navigation bar has no height", navigationBar > 0)
    val node = onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
    val bottom = node.positionOnScreen.y + node.size.height
    assertTrue("$name bottom=$bottom screenBottom=$screenBottom navigationBar=$navigationBar",
        abs(bottom - screenBottom) <= 1f)
}
