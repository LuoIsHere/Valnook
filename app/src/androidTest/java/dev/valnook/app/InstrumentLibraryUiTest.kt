package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.feature.investments.InstrumentLibraryActions
import dev.valnook.feature.investments.InstrumentLibraryContent
import dev.valnook.feature.investments.InstrumentLibraryState
import dev.valnook.feature.investments.InstrumentLibraryToolbarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/** All instruments as rows of one card: the whole name, its price per unit and profits, filtered by class. */
@HiltAndroidTest
class InstrumentLibraryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val stocks = AssetType(1, "合成股票")
    private val funds = AssetType(2, "合成海外基金")
    private val empty = AssetType(3, "合成空类型")
    private fun instrument(id: Long, name: String, symbol: String, type: AssetType, code: String, price: String) =
        Instrument(id, name, symbol, type.id, type.name, Currency.of(code), BigDecimal(price).movePointRight(5).toLong(), true, 1, 0)
    private fun assets(instrument: Instrument, floating: String, realized: String) =
        InstrumentAssets(instrument, BigDecimal.ZERO, BigDecimal(floating), BigDecimal(realized))
    private val held = assets(instrument(1, "合成欧洲基金", "SYNEU01", funds, "HKD", "12.34"), "1.23", "0")
    private val shortSold = assets(instrument(2, "短名基金", "SHORT", funds, "HKD", "9.8765"), "0", "3.2")
    private val longSold = assets(instrument(3, "合成全天候高息股票基金 A 类 累积 港元对冲 另加一段很长的说明文字让名称超过两行再长一些",
        "SYNHK02", funds, "HKD", "7.6543"), "0", "-0.05")
    private val loss = assets(instrument(4, "Synthetic Memory ETF", "SYNM", stocks, "USD", "45.67"), "-6.54", "0")
    private val state = InstrumentLibraryState.Ready(listOf(held, shortSold, longSold, loss), listOf(stocks, funds, empty), setOf(2L, 3L))
    private var opened = mutableListOf<Long>()

    private fun show(dark: Boolean = false, fontScale: Float = 1f, current: InstrumentLibraryState.Ready = state) {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Surface(Modifier.safeDrawingPadding()) { InstrumentLibraryContent(current, { opened += it }, {}) }
            }
        } } }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("instrument-library-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.first()
    }

    /** Either beside a one-line name, or at the start of the second line with the symbol after it. */
    private fun tagPlacement(id: Long): Boolean? {
        val name = bounds("library-name-$id")
        val tag = bounds("library-not-held-$id")
        val meta = bounds("library-meta-$id")
        val beside = tag.left >= name.right && tag.top < name.bottom && tag.bottom <= meta.top + 1f && layout("library-name-$id").lineCount == 1
        val under = tag.top >= name.bottom && kotlin.math.abs(tag.left - name.left) <= 1f && meta.left >= tag.right
        return if (beside) true else if (under) false else null
    }

    /** A tag that fits beside the name stays there; one that does not starts the second line instead. */
    private fun assertTagPlacement(shortFits: Boolean = true) {
        val short = tagPlacement(2)
        assertTrue("short: tag beside the name or starting the second line", short != null)
        if (shortFits) assertEquals("short: tag follows the name", true, short)
        val longName = bounds("library-name-3")
        val longTag = bounds("library-not-held-3")
        assertTrue("long: tag under the name", longTag.top >= longName.bottom)
        assertEquals("long: tag starts the line", longName.left, longTag.left, 1f)
        assertTrue("long: symbol after the tag", bounds("library-meta-3").left >= longTag.right)
        val name = layout("library-name-3")
        assertEquals(2, name.lineCount)
        assertTrue("long names end in an ellipsis", name.isLineEllipsized(1))
        rule.onNodeWithTag("library-not-held-1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun rows_show_price_and_profits_with_the_not_held_tag_beside_or_under_the_name() {
        show(dark = true); save("dark")
        show(); save("light")
        assertTagPlacement()
        rule.onNodeWithTag("library-row-1")
            .assert(hasText("合成欧洲基金", substring = true))
            .assert(hasText("SYNEU01 · HKD · 合成海外基金"))
            .assert(hasText("/ 份"))
            .assert(hasText("浮动盈亏 +1.23 HKD"))
            .assert(hasText("已实现盈亏 0.00 HKD"))
        rule.onNodeWithTag("library-price-1", useUnmergedTree = true).assertTextEquals("12.34 HKD")
        rule.onNodeWithTag("library-price-2", useUnmergedTree = true).assertTextEquals("9.8765 HKD")
        rule.onNodeWithTag("library-row-3").assert(hasText("未持有", substring = true)).assert(hasText("已实现盈亏 -0.05 HKD"))
        rule.onNodeWithTag("library-row-4").assert(hasText("浮动盈亏 -6.54 USD"))
        rule.onNodeWithText("共 4 个标的").assertExists()
        rule.onNodeWithTag("library-row-3").performClick()
        assertEquals(listOf(3L), opened)
    }

    @Test fun large_text_keeps_the_tag_rule_and_the_price_whole() {
        show(fontScale = 2f); save("large-text")
        // At twice the size even a short name may leave no room beside it: then the tag moves down too.
        assertTagPlacement(shortFits = false)
        for (id in 1..2) {
            val row = bounds("library-row-$id")
            val price = bounds("library-price-$id")
            assertTrue("price $id stays inside its row", price.right <= row.right + 0.5f && price.left >= row.left)
            assertTrue("price $id is not cut", !layout("library-price-$id").hasVisualOverflow)
        }
    }

    @Test fun capsules_filter_by_class_and_search_narrows_and_clears() {
        show()
        fun capsule(name: String) = hasText(name) and hasAnyAncestor(hasTestTag("instrument-type-filter"))
        rule.onNode(capsule("全部")).assert(hasText("4")).assertIsSelected()
        rule.onNode(capsule("合成股票")).assert(hasText("1")).performClick()
        rule.onNode(capsule("合成股票")).assertIsSelected()
        rule.onNodeWithTag("library-row-4").assertExists()
        rule.onNodeWithTag("library-row-1").assertDoesNotExist()
        rule.onNodeWithText("共 1 个标的").assertExists()
        rule.onNodeWithTag("instrument-type-filter").performScrollToNode(capsule("合成空类型"))
        rule.onNode(capsule("合成空类型")).assert(hasText("0")).performClick()
        rule.onNodeWithText("该资产类型下暂无标的").assertExists()
        rule.onNodeWithTag("instrument-library-card").assertDoesNotExist()
        rule.onNodeWithTag("instrument-type-filter").performScrollToNode(capsule("全部"))
        rule.onNode(capsule("全部")).performClick()
        rule.onNodeWithTag("instrument-library-search").performTextInput("synm")
        rule.onNodeWithTag("library-row-4").assertExists()
        rule.onNodeWithTag("library-row-1").assertDoesNotExist()
        save("search")
        rule.onNodeWithContentDescription("清除搜索").performClick()
        rule.onNodeWithText("共 4 个标的").assertExists()
        rule.onNodeWithContentDescription("清除搜索").assertDoesNotExist()
    }

    @Test fun without_classes_the_page_asks_for_one_and_adding_waits() {
        show(current = InstrumentLibraryState.Ready(emptyList(), emptyList()))
        rule.onNodeWithText("请先创建资产类型，再新增标的").assertExists()
        rule.onNodeWithTag("instrument-type-filter").assertDoesNotExist()
        rule.onNodeWithText("尚无标的").assertDoesNotExist()
        save("no-types")
        var types = 0; var adds = 0
        for (canCreate in listOf(false, true)) {
            rule.runOnUiThread { rule.activity.setContent { ValnookTheme { Surface { Row {
                InstrumentLibraryActions(InstrumentLibraryToolbarState(canCreate), { types++ }, { adds++ })
            } } } } }
            rule.waitForIdle()
            rule.onNodeWithContentDescription("资产类型管理").assertIsEnabled().performClick()
            if (canCreate) rule.onNodeWithContentDescription("新增标的").assertIsEnabled().performClick()
            else rule.onNodeWithContentDescription("新增标的").assertIsNotEnabled()
        }
        assertEquals(2, types); assertEquals(1, adds)
    }
}
