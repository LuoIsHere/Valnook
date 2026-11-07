package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.CollapsingBalanceHeader
import dev.valnook.designsystem.HeaderCollapse
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.designsystem.rememberHeaderCollapse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Halfway closed, the balance never sits on the details above it: they are gone before it starts to rise. */
@HiltAndroidTest
class CollapsingHeaderUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private lateinit var collapse: HeaderCollapse

    private fun show() {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme { Surface(Modifier.safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                collapse = rememberHeaderCollapse("collapsing-header-test")
                CollapsingBalanceHeader(collapse, "110.00 CNY", MaterialTheme.colorScheme.onSurface, {},
                    Modifier.testTag("header"), Modifier.testTag("balance"), top = { fading ->
                        Column(fading, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("SYNH · CNY · 合成基金", Modifier.testTag("top-code"), style = MaterialTheme.typography.bodySmall)
                            Text("浮动盈亏 0.00 (0.00%)", Modifier.testTag("top-line"), style = MaterialTheme.typography.bodySmall)
                        }
                    }) { chart -> Box(chart.fillMaxWidth()) }
                Box(Modifier.fillMaxWidth().height(600.dp))
            }
        } } } }
        rule.waitForIdle()
    }

    private fun closeTo(target: Float) {
        rule.runOnUiThread { collapse.dragBy(-(target - collapse.progress) * collapse.rangePx) }
        rule.waitForIdle()
        assertEquals(target, collapse.progress, .01f)
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("collapsing-header-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    @Test fun the_balance_waits_below_the_details_until_they_have_faded() {
        show()
        val restingTop = bounds("balance").top
        for (step in listOf(.05f, .1f, .15f, .19f)) {
            closeTo(step)
            // Still showing, the details keep the balance where it rests, below them.
            assertTrue("at $step the details still show", collapse.detailsAlpha > 0f)
            assertEquals("at $step the balance has not moved", restingTop, bounds("balance").top, 1f)
            assertTrue("at $step the balance stays below the details", bounds("balance").top >= bounds("top-line").bottom - 1f)
            if (step == .15f) save("15")
        }
        for (step in listOf(.2f, .3f, .6f, .9f)) {
            closeTo(step)
            assertEquals("at $step the details are gone", 0f, collapse.detailsAlpha, 0f)
            if (step == .3f) save("30")
            if (step == .6f) save("60")
        }
        // Closed, the balance has risen out of the header into the title bar's height.
        closeTo(1f)
        assertTrue(bounds("balance").top < restingTop)
    }
}
