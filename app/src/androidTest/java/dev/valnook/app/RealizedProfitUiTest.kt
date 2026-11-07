package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.valnook.domain.money.DecimalRules
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.feature.investments.R as InvestmentsR
import dev.valnook.feature.investments.RealizedProfitContent
import dev.valnook.feature.investments.RealizedRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal

/** Realized profit by instrument: the total, then one card of instruments, opened from the investments page. */
@HiltAndroidTest
class RealizedProfitUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val cny = Currency.of("CNY")
    private val funds = AssetType(2, "合成基金")
    private val stocks = AssetType(3, "合成美股")
    private fun row(id: Long, name: String, symbol: String, type: AssetType, currency: Currency, realized: String?,
        held: Boolean, inBase: String? = null) = RealizedRow(InstrumentAssets(
            Instrument(id, name, symbol, type.id, type.name, currency, 100_000, true, 1, 0), BigDecimal.ZERO, null,
            realized?.let(::BigDecimal)), held, inBase?.let(::BigDecimal))

    private val rows = listOf(
        row(1, "合成科技指数基金", "SYNT", funds, cny, "1240", held = false),
        row(2, "示例全市场 ETF", "DEMO", stocks, Currency.of("USD"), "-86.40", held = true, inBase = "-615.168"),
        row(3, "合成高股息基金 A 类 累积 港元 另加一段较长的名称让它折成两行", "SYDV", funds, Currency.of("HKD"), "120", held = true,
            inBase = "110.4"),
        row(4, "示例短债基金", "BOND", funds, cny, "-32.10", held = false),
        row(5, "合成记录有误的基金", "BAD", funds, cny, null, held = true))
    private val total = ConvertedTotal(BigDecimal("703.132"), cny, emptySet())
    private val opened = mutableListOf<Long>()

    private fun show(dark: Boolean = false, fontScale: Float = 1f, shown: List<RealizedRow> = rows,
        sum: ConvertedTotal = total) {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme = dark) {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                Surface(Modifier.safeDrawingPadding()) { RealizedProfitContent(sum, cny, shown) { opened += it } }
            }
        } } }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("realized-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    @Test fun rows_show_each_amount_in_its_currency_and_the_base_amount_for_foreign_ones() {
        show()
        rule.onNodeWithTag("realized-total").assertTextEquals("703.13 CNY")
        rule.onNodeWithText(rule.activity.resources.getQuantityString(InvestmentsR.plurals.realized_count, 5, 5)).assertExists()
        // In the order given, one row under another.
        val tops = (1L..5L).map { bounds("realized-row-$it").top }
        assertEquals(tops.sorted(), tops)
        rule.onNodeWithTag("realized-amount-1", useUnmergedTree = true).assertTextEquals("+" + DecimalRules.format_display(BigDecimal("1240"), 2) + " CNY")
        rule.onNodeWithTag("realized-amount-2", useUnmergedTree = true).assertTextEquals("-86.40 USD")
        rule.onNodeWithTag("realized-base-2", useUnmergedTree = true).assertTextEquals(string(InvestmentsR.string.realized_in_base, "-615.17 CNY"))
        rule.onNodeWithTag("realized-base-3", useUnmergedTree = true).assertTextEquals(string(InvestmentsR.string.realized_in_base, "+110.40 CNY"))
        // Amounts already in the base currency are not said twice.
        rule.onNodeWithTag("realized-base-1", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("realized-amount-5", useUnmergedTree = true).assertTextEquals(string(InvestmentsR.string.realized_unknown))
        rule.onNodeWithTag("library-meta-1", useUnmergedTree = true)
            .assertTextEquals("SYNT · CNY · " + string(InvestmentsR.string.realized_sold_out))
        rule.onNodeWithTag("library-meta-2", useUnmergedTree = true)
            .assertTextEquals("DEMO · USD · " + string(InvestmentsR.string.realized_held))
        // The amount stays beside the name, on its first line.
        assertTrue(bounds("realized-amount-3").left >= bounds("library-name-3").right - 1)
        save("light")
        rule.onNodeWithTag("realized-row-2").performClick()
        assertEquals(listOf(2L), opened)
    }

    @Test fun dark_and_large_type_keep_every_amount_whole() {
        show(dark = true)
        save("dark")
        show(fontScale = 2f)
        rule.onNodeWithTag("realized-page").performScrollToNode(hasTestTag("realized-row-5"))
        listOf("amount-1", "amount-2", "base-2", "amount-4").forEach { id ->
            val amount = rule.onNodeWithTag("realized-$id", useUnmergedTree = true).fetchSemanticsNode()
            val layout = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            amount.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layout)
            assertTrue("Amount $id is not cut", !layout.single().hasVisualOverflow)
        }
        save("large-type")
    }

    @Test fun nothing_realized_says_so() {
        show(shown = emptyList(), sum = ConvertedTotal(BigDecimal.ZERO, cny, emptySet()))
        rule.onNodeWithText(string(InvestmentsR.string.realized_empty)).assertExists()
        rule.onNodeWithTag("realized-card").assertDoesNotExist()
    }

    @Test fun the_investments_total_opens_the_page_under_its_title() {
        rule.runOnUiThread { rule.activity.setContent { ValnookRoot(rule.activity.sessions, rule.activity.webAdmin) } }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-investments").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-investments").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("investment-realized-open").fetchSemanticsNodes().isNotEmpty() }
        // A button that says where it goes, with the label and the figure in it.
        rule.onNodeWithTag("investment-realized-open")
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,
                androidx.compose.ui.semantics.Role.Button))
            .assertTextContains(string(InvestmentsR.string.investment_total_realized), substring = true)
        assertTrue("A 48dp target", bounds("investment-realized-open").height >=
            with(rule.density) { 48.dp.toPx() } - 1)
        // The reasons under the two figures stay level.
        val reasons = rule.onAllNodesWithText(string(InvestmentsR.string.investment_incomplete,
            string(InvestmentsR.string.investment_missing_base))).fetchSemanticsNodes().map { it.boundsInRoot.top }
        assertEquals(2, reasons.size)
        assertEquals(reasons[0], reasons[1], 1f)
        save("entry")
        rule.onNodeWithTag("investment-realized-open").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("realized-page").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(string(InvestmentsR.string.realized_empty)).assertExists()
        assertTrue(rule.onAllNodesWithText(string(InvestmentsR.string.realized_title)).fetchSemanticsNodes().isNotEmpty())
    }
}
