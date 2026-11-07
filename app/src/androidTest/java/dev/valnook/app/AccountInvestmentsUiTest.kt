package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.OverviewRepository
import dev.valnook.feature.investments.AccountInvestments
import dev.valnook.feature.investments.PortfolioViewModel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode
import dev.valnook.feature.investments.R as InvestmentsR

/** An account's investments read as rows of one card: the whole name, value and profit, then units, price and cost. */
@HiltAndroidTest
class AccountInvestmentsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val cny = Currency.of("CNY")
    private fun e8(text: String) = DecimalRules.parse_e8(text)
    private fun position(id: Long, account: Long, name: String, quantity: String, price: String, cost: String?,
        realized: String = "0", closed: Boolean = false) = Investment(id, account, 1, "Fund", name, "SYN00000$id", cny,
        e8(quantity), e8(price), 0, remainingCost = cost, realizedProfit = realized, isClosed = closed)
    private val gain = position(1, 10, "合成全天候高息股票基金 A2 累积 港元对冲类别", "321.5", "8.7654", "2500.00")
    private val loss = position(2, 10, "合成欧洲收益基金 AT 股", "3.1", "55.55", "200.00")
    private val closed = position(3, 10, "合成债券基金", "0", "1", null, realized = "3.20", closed = true)
    private val elsewhere = position(4, 20, "Other account holding", "1", "1", "1")
    private val snapshot = AssetSnapshot(listOf(SavingsAccount(10, "Example bank", ""), SavingsAccount(20, "Other", "")),
        emptyList(), emptyList(), listOf(gain, loss, closed, elsewhere), emptyList(), AppSettings(baseCurrency = cny))
    private var opened = mutableListOf<Long>()
    private var added = 0
    private var archived = 0

    private fun show(all: Boolean, dark: Boolean = false) {
        val repository = object : OverviewRepository {
            override fun observeSnapshot() = flowOf(snapshot)
            override suspend fun snapshot() = snapshot
        }
        val vm = rule.runOnUiThread { PortfolioViewModel(repository) }
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme = dark) { Surface(Modifier.safeDrawingPadding()) {
            AccountInvestments(vm, 10, all, { opened += it }, { added++ }, { archived++ })
        } } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("account-investments-card").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("account-investments-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun money(value: BigDecimal) = DecimalRules.format_display(value, 2)
    private fun signed(value: BigDecimal) = (if (value.setScale(2, RoundingMode.HALF_UP).signum() > 0) "+" else "") + money(value)

    @Test fun holdings_show_value_profit_and_details_with_the_account_profit_above() {
        show(false, dark = true); save("current-dark")
        show(false); save("current-light")
        for (held in listOf(gain, loss)) {
            val profit = InvestmentProfitCalculator.fromReadModel(held)
            rule.onNodeWithTag("holding-${held.id}")
                .assert(hasText(held.name, substring = true))
                .assert(hasText(money(AssetValuation.marketValue(held)) + " CNY", substring = true))
                .assert(hasText(rule.activity.getString(InvestmentsR.string.investment_holding_detail,
                    DecimalRules.format_e8(held.holding_quantity_e8), DecimalRules.format_e8(held.current_price_e8),
                    profit.average_cost!!.setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()), substring = true))
                .assert(hasText(signed(profit.unrealizedPercent!!) + "%", substring = true))
            rule.onNodeWithTag("holding-profit-${held.id}", useUnmergedTree = true).assertTextEquals(signed(profit.unrealized!!))
        }
        // Closed ones and other accounts' are not among the current holdings.
        rule.onNodeWithTag("holding-3").assertDoesNotExist()
        rule.onNodeWithTag("holding-4").assertDoesNotExist()
        val floating = listOf(gain, loss).map { InvestmentProfitCalculator.fromReadModel(it).unrealized!! }.fold(BigDecimal.ZERO, BigDecimal::add)
        rule.onNodeWithTag("account-investments-floating").assert(hasText(signed(floating) + " CNY"))
        rule.onNodeWithTag("account-investments-realized").assert(hasText("+3.20 CNY"))
        // The closed list is beside the section's name; adding is the card's last row, under the holdings.
        val header = rule.onNodeWithTag("account-investments-header").fetchSemanticsNode().boundsInRoot
        val add = rule.onNodeWithTag("account-investments-add").fetchSemanticsNode().boundsInRoot
        val last = rule.onNodeWithTag("holding-2").fetchSemanticsNode().boundsInRoot
        assertTrue(add.top >= last.bottom && last.top > header.bottom)
        rule.onNodeWithTag("account-investments-closed").performClick()
        rule.onNodeWithTag("account-investments-add").performClick()
        rule.onNodeWithTag("holding-1").performClick()
        assertEquals(1, archived); assertEquals(1, added); assertEquals(listOf(1L), opened)
    }

    /** On the account page itself: the profit card holds its numbers, and the section's title sits close to both cards. */
    @Test fun account_tab_keeps_the_profit_card_whole_and_the_title_tight() {
        val graph = rule.activity.sessions.session.value.graph
        runBlocking { dev.valnook.app.di.DemoDataSeeder(graph, graph.clock).seed(AppSettings()) }
        val accountId = runBlocking { graph.overview.snapshot() }.positions.first { it.holding_quantity_e8 > 0 }.account_id
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("account-total-$accountId").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("account-total-$accountId").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("account-section-INVESTMENTS").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("account-section-INVESTMENTS").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("account-investments-profit").fetchSemanticsNodes().isNotEmpty() }
        save("account-page")
        fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val card = bounds("account-investments-profit")
        for (tag in listOf("account-investments-floating", "account-investments-realized"))
            assertTrue("$tag must stay inside its card", bounds(tag).bottom <= card.bottom + 0.5f)
        val density = rule.activity.resources.displayMetrics.density
        // Measured on the title's own text: the row around it must not push the cards apart.
        val title = rule.onNode(hasText(rule.activity.getString(InvestmentsR.string.investment_current_holdings)) and
            hasAnyAncestor(hasTestTag("account-investments-header")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val header = bounds("account-investments-header")
        val list = bounds("account-investments-card")
        assertTrue("title row: ${header.height / density}dp", header.height <= 34 * density)
        assertTrue("gap above the title: ${(title.top - card.bottom) / density}dp", title.top - card.bottom <= 20 * density)
        assertTrue("gap below the title: ${(list.top - title.bottom) / density}dp", list.top - title.bottom <= 16 * density)
    }

    @Test fun closed_positions_show_what_they_realized() {
        show(true); save("closed")
        rule.onNodeWithTag("holding-3")
            .assert(hasText(rule.activity.getString(InvestmentsR.string.instrument_closed_position), substring = true))
            .assert(hasText("+3.20 CNY", substring = true))
        rule.onNodeWithTag("holding-1").assertDoesNotExist()
        rule.onNodeWithTag("account-investments-profit").assertDoesNotExist()
        rule.onNodeWithTag("account-investments-add").assertDoesNotExist()
        rule.onNodeWithTag("holding-3").performClick()
        assertEquals(listOf(3L), opened)
    }
}
