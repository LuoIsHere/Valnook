package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.TrendChart
import dev.valnook.designsystem.ValnookTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

/** The dot on the latest value shows whole once the line has been drawn in. */
class TrendChartEndDotUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun endDotRightHalfIsDrawn(compact: Boolean) {
        rule.setContent {
            ValnookTheme { Box {
                TrendChart(listOf(.5f, .5f, .5f), emptyList(), null, {}, curvePath = { points ->
                    Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                }, description = "trend", readout = "", compact = compact,
                    modifier = Modifier.width(200.dp).height(100.dp).testTag("chart"))
            } }
        }
        rule.waitForIdle()
        val image = rule.onNodeWithTag("chart").captureToImage().toPixelMap()
        val density = rule.density.density
        // The last point sits 5dp in from the right edge, halfway down the plot; the dot's radius is 2.5dp.
        val x = (image.width - 5 * density + 1.5f * density).roundToInt()
        val labels = if (compact) 0f else 27 * density
        val y = ((5 * density + (image.height - labels - 10 * density) * .5f)).roundToInt()
        val background = image[1, 1]
        val drawn = (-1..1).any { dy -> image[x, y + dy] != background }
        assertTrue("The dot's right half is missing", drawn)
    }

    @Test fun full_chart_shows_the_whole_end_dot() = endDotRightHalfIsDrawn(compact = false)

    @Test fun sparkline_shows_the_whole_end_dot() = endDotRightHalfIsDrawn(compact = true)
}
