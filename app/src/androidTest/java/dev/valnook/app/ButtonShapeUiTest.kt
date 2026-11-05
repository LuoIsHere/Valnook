package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.ActionButton
import dev.valnook.designsystem.ValnookTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** A filled Button and an ActionButton side by side look like one pair, whatever the text size. */
class ButtonShapeUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(fontScale: Float) = rule.setContent {
        val density = LocalDensity.current.density
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            ValnookTheme { Box {
                Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(6.dp)) {
                    Button({}, Modifier.weight(1f).testTag("filled")) { Text("Open") }
                    ActionButton({}, Modifier.weight(1f).testTag("action")) { Text("Open") }
                }
            } }
        }
    }

    private fun height(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height

    @Test fun same_height_at_normal_size() {
        show(1f)
        assertEquals(height("filled"), height("action"), 0.5f)
    }

    @Test fun same_height_at_large_size() {
        show(2f)
        assertEquals(height("filled"), height("action"), 0.5f)
    }

    /** Only the outline matters here, so the fill colors and the text are read as "not the page background". */
    @Test fun same_outline() {
        show(1f)
        val filled = rule.onNodeWithTag("filled", useUnmergedTree = true).captureToImage().toPixelMap()
        val action = rule.onNodeWithTag("action", useUnmergedTree = true).captureToImage().toPixelMap()
        assertEquals(filled.width.toFloat(), action.width.toFloat(), 2f)
        assertEquals(filled.height, action.height)
        val page = filled[0, 0]
        var different = 0
        for (x in 0 until minOf(filled.width, action.width)) for (y in 0 until filled.height) {
            if ((filled[x, y] == page) != (action[x, y] == page)) different++
        }
        assertTrue("$different pixels differ", different < filled.width * filled.height / 50)
    }
}
