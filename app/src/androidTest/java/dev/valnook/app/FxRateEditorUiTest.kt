package dev.valnook.app

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.Currency
import dev.valnook.feature.settings.FxRateDraft
import dev.valnook.feature.settings.FxRateEditor
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The grouped rate editor: ⊖ removes, the last line adds, the code changes the currency, the field takes the rate. */
class FxRateEditorUiTest {
    @get:Rule val rule = createComposeRule()
    private val cny = Currency.of("CNY")
    private val rows = mutableStateListOf(FxRateDraft(Currency.of("USD"), "6.66"), FxRateDraft(Currency.of("GBP"), "8.958967926895"))
    private var added = 0

    private fun show(dark: Boolean = false, fontScale: Float = 1f, width: Int = 400, readOnly: Boolean = false) {
        rule.setContent {
            ValnookTheme(dark_theme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width.dp).background(MaterialTheme.colorScheme.background).padding(16.dp)
                        .testTag("fx-editor-shot")) {
                        FxRateEditor(cny, rows, busy = false, onBase = {}, onAdd = { added++ },
                            onUpdate = { index, source, rate ->
                                rows[index] = rows[index].copy(sourceCurrency = source ?: rows[index].sourceCurrency,
                                    rateInput = rate ?: rows[index].rateInput)
                            }, onRemove = { rows.removeAt(it) }, readOnly = readOnly)
                    }
                }
            }
        }
    }

    private fun save(name: String) {
        val bitmap = rule.onNodeWithTag("fx-editor-shot").captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("fx-editor-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun field(index: Int) = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-$index")))

    @Test fun rows_edit_in_place_remove_add_and_change_currency() {
        show()
        save("light")
        field(1).assert(hasText("8.958967926895"))
        field(0).performTextReplacement("7.1")
        rule.runOnIdle { assertEquals("7.1", rows[0].rateInput) }

        rule.onNode(hasText("GBP") and hasClickAction()).performClick()
        rule.onNodeWithTag("currency-list").assertExists()
        rule.onNodeWithText("搜索代码或币种名称").performTextInput("EUR")
        rule.onNode(hasText("EUR") and hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        rule.runOnIdle { assertEquals("EUR", rows[1].sourceCurrency.code) }

        rule.onNodeWithTag("remove-rate-USD").performClick()
        rule.runOnIdle { assertEquals(listOf("EUR"), rows.map { it.sourceCurrency.code }) }
        rule.onNodeWithTag("fx-add").performClick()
        rule.runOnIdle { assertEquals(1, added) }
    }

    @Test fun automatic_mode_shows_text_without_remove_or_add() {
        show(readOnly = true)
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        rule.onAllNodesWithTag("fx-add").assertCountEquals(0)
        rule.onAllNodesWithTag("remove-rate-USD").assertCountEquals(0)
        rule.onNode(hasAnyAncestor(hasTestTag("fx-rate-1")) and hasText("8.958967926895"), useUnmergedTree = true).assertExists()
        save("read-only")
    }

    @Test fun dark_theme_shot() { show(dark = true); save("dark") }

    @Test fun narrow_large_font_keeps_the_rate_field_usable() {
        show(fontScale = 1.3f, width = 320)
        save("narrow-large-font")
        field(1).assertIsDisplayed().performTextReplacement("9.5")
        rule.runOnIdle { assertEquals("9.5", rows[1].rateInput) }
        rule.onNodeWithTag("remove-rate-GBP").assertIsDisplayed()
    }
}
