package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.LocalPageTopBarHeight
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.Direction
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import dev.valnook.feature.investments.InstrumentDetail
import dev.valnook.feature.investments.InstrumentDetailViewModel
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** One instrument's page adds up every account, lists each one, and trades from the account chosen. */
@HiltAndroidTest
class InstrumentDetailUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val graph get() = rule.activity.sessions.session.value.graph
    private val bought = mutableListOf<Pair<Long, Long?>>()
    private val sold = mutableListOf<Pair<Long, Long>>()
    private val opened = mutableListOf<Pair<Long, Long>>()
    private var instrument = 0L
    private var savings = 0L
    private var broker = 0L
    private var closed = 0L
    private var savingsPosition = 0L
    private var brokerPosition = 0L
    private lateinit var vm: InstrumentDetailViewModel

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }
    @After fun close() { rule.runOnUiThread { store.clear() } }

    private fun id() = UUID.randomUUID().toString()
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Bank holds 10 at 100, Broker 5 at 120, Old bank bought 2 at 100 and sold them at 115; the price is 120. */
    private fun seed() = runBlocking {
        val commands = graph.commands
        val type = commands.execute(SaveAssetType(id(), null, "基金")).id
        savings = commands.execute(SaveAccount(id(), null, null, "Bank", "", emptyList())).id
        broker = commands.execute(SaveAccount(id(), null, null, "Broker", "", emptyList())).id
        closed = commands.execute(SaveAccount(id(), null, null, "Old bank", "", emptyList())).id
        instrument = commands.execute(SaveInstrument(id(), null, null, "Blue chip fund", "005827", type, "CNY", 12_000_000)).id
        savingsPosition = commands.execute(CreateInvestmentPosition(id(), savings, instrument)).id
        brokerPosition = commands.execute(CreateInvestmentPosition(id(), broker, instrument)).id
        val closedPosition = commands.execute(CreateInvestmentPosition(id(), closed, instrument)).id
        commands.execute(RecordInvestmentTrade(id(), savingsPosition, Direction.BUY, R.parse_e8("10"), R.parse_e8("100"), at(1, 10), false))
        commands.execute(RecordInvestmentTrade(id(), brokerPosition, Direction.BUY, R.parse_e8("5"), R.parse_e8("120"), at(2, 9), false))
        commands.execute(RecordInvestmentTrade(id(), closedPosition, Direction.BUY, R.parse_e8("2"), R.parse_e8("100"), at(1, 9), false))
        commands.execute(RecordInvestmentTrade(id(), closedPosition, Direction.SELL, R.parse_e8("2"), R.parse_e8("115"), at(2, 10), false))
    }

    private fun show(contextAccount: Long? = null) {
        rule.runOnUiThread {
            vm = InstrumentDetailViewModel(instrument, graph.overview, graph.investments, graph.instruments,
                SavedStateHandle(), graph.clock, graph.commands)
            store.put("instrument", vm)
            rule.activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
                CompositionLocalProvider(LocalContext provides LocalContext.current.createConfigurationContext(config),
                    LocalConfiguration provides config, LocalPageTopBarHeight provides 56.dp) {
                    ValnookTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.padding(top = 56.dp).width(360.dp).height(720.dp)) {
                        InstrumentDetail(vm, contextAccount, {}, { account, trade -> opened += account to trade },
                            { account, position -> bought += account to position }, { account, position -> sold += account to position })
                    } } }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("instrument-summary").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        rule.waitForIdle()
        // The whole screen, so a dialog in its own window is included.
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("instrument-$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun list() = rule.onNodeWithTag("instrument-detail-list")
    /** Rows below the screen are not composed, so count what the page holds. */
    private fun shownTrades() = vm.trades.value.rows.size

    @Test fun totals_and_accounts_cover_every_account_whichever_way_it_opened() {
        seed(); show()
        // 15 units worth 1 800.00 at 120, costing 1 600.00: +200.00, 12.50%.
        rule.onNodeWithTag("instrument-value", useUnmergedTree = true).assertTextContains("1 800.00 CNY")
        rule.onNodeWithTag("instrument-header-line").assertTextContains("+200.00 (+12.50%)", substring = true)
        rule.onNodeWithTag("instrument-quantity").assertTextContains("15", substring = true)
        rule.onNodeWithTag("instrument-average").assertTextContains("106.66667", substring = true)
        rule.onNodeWithTag("instrument-cost").assertTextContains("1 600.00", substring = true)
        rule.onNodeWithTag("instrument-realized").assertTextContains("+30.00", substring = true)
        save("summary")
        // Held accounts first, the larger one first; the closed one after with what it realized.
        list().performScrollToNode(hasTestTag("instrument-account-$closed"))
        val tops = listOf(savings, broker, closed).map {
            rule.onNodeWithTag("instrument-account-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(tops.sorted(), tops)
        rule.onNodeWithTag("instrument-account-$closed").assertTextContains("已清仓", substring = true)
            .assertTextContains("已实现 +30.00", substring = true)
        rule.onNodeWithTag("instrument-account-$broker").assertTextContains("5 份 · 成本 120", substring = true)
        // October has all four trades, newest first, each with its account.
        list().performScrollToNode(hasTestTag("month-next"))
        rule.onNodeWithTag("ledger-month").assertTextEquals("2026年10月")
        rule.waitUntil(5_000) { shownTrades() == 4 }
        list().performScrollToNode(hasText("卖出 · Old bank"))
        rule.onAllNodes(hasText("卖出 · Old bank")).assertCountEquals(1)
        save("history")
    }

    @Test fun the_history_shows_one_account_from_the_menu_or_its_row() {
        seed(); show()
        list().performScrollToNode(hasTestTag("instrument-trade-filter"))
        rule.onNodeWithTag("instrument-trade-filter").assertTextContains("全部账户").performClick()
        rule.onNodeWithTag("instrument-trade-filter-$broker").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("instrument-trade-filter").assertTextContains("Broker")
        rule.waitUntil(5_000) { shownTrades() == 1 }
        rule.onAllNodes(hasText("买入 · Broker")).assertCountEquals(1)
        save("filtered")
        // A tap on another account's row shows that one; a second tap all of them again.
        list().performScrollToNode(hasTestTag("instrument-account-$closed"))
        rule.onNodeWithTag("instrument-account-$closed").performClick().assertIsSelected()
        rule.waitUntil(5_000) { shownTrades() == 2 }
        rule.onNodeWithTag("instrument-account-$closed").performClick().assertIsNotSelected()
        rule.waitUntil(5_000) { shownTrades() == 4 }
        list().performScrollToNode(hasTestTag("instrument-trade-filter"))
        rule.onNodeWithTag("instrument-trade-filter").assertTextContains("全部账户")
    }

    @Test fun buying_and_selling_choose_the_account_starting_with_the_one_it_came_from() {
        seed(); show(contextAccount = broker)
        rule.onNodeWithTag("instrument-buy").performClick()
        rule.onNodeWithTag("instrument-account-picker").assertExists()
        // Every account can buy; the one it came from is first and marked.
        val picks = listOf(broker, savings, closed).map { rule.onNodeWithTag("instrument-account-pick-$it").fetchSemanticsNode().boundsInRoot.top }
        assertEquals(picks.sorted(), picks)
        rule.onNodeWithTag("instrument-account-pick-$broker").assertIsSelected()
        save("buy-picker")
        rule.onNodeWithTag("instrument-account-pick-$closed").performClick()
        rule.waitForIdle()
        assertEquals(1, bought.size)
        assertEquals(closed, bought.single().first)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("instrument-account-picker").fetchSemanticsNodes().isEmpty() }
        // Only accounts holding units can sell.
        rule.onNodeWithTag("instrument-sell").performClick()
        rule.onNodeWithTag("instrument-account-pick-$closed").assertDoesNotExist()
        rule.onNodeWithTag("instrument-account-pick-$savings").performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("instrument-account-picker").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf(savings to savingsPosition), sold)
        // A trade opens with its own account.
        list().performScrollToNode(hasText("买入 · Bank"))
        rule.onNode(hasText("买入 · Bank")).performClick()
        assertEquals(savings, opened.single().first)
    }

    @Test fun the_price_trend_shows_a_touched_update_in_the_header() = runBlocking<Unit> {
        seed()
        listOf(12_500_000L, 12_500_000L, 11_800_000L).forEach { price ->
            val revision = graph.instruments.observeInstrument(instrument).first()!!.revision
            graph.commands.execute(UpdateInstrumentPrices(id(), listOf(InstrumentPriceChange(instrument, revision, price)), true))
        }
        show()
        val line = rule.onNodeWithTag("instrument-header-line")
        line.assertTextContains("浮动盈亏", substring = true)
        val chart = rule.onNodeWithTag("instrument-price-trend")
        assertTrue(chart.fetchSemanticsNode().boundsInRoot.width > 200f)
        chart.performTouchInput { down(centerLeft + androidx.compose.ui.geometry.Offset(4f, 0f)) }
        rule.waitForIdle()
        // The oldest of the recent prices is the one the instrument was created with.
        line.assertTextContains("120 CNY", substring = true)
        save("trend-touched")
        chart.performTouchInput { up() }
        rule.waitForIdle()
        line.assertTextContains("浮动盈亏", substring = true)
    }

    private fun prices() = (vm.state.value as dev.valnook.feature.investments.InstrumentDetailState.Ready).prices
    private fun dialogGone() = rule.waitUntil(5_000) { rule.onAllNodesWithTag("instrument-price-dialog").fetchSemanticsNodes().isEmpty() }

    @Test fun the_price_dialog_records_a_save_even_at_the_same_price() {
        seed(); show()
        val before = prices().size
        rule.onNodeWithTag("instrument-price-update").performClick()
        rule.onNodeWithTag("instrument-price-dialog").assertExists()
        rule.onNodeWithText("上次更新", substring = true).assertExists()
        save("price-dialog")
        // Saved unchanged: still a new point on the trend.
        rule.onNodeWithTag("instrument-price-save").performClick()
        dialogGone()
        rule.waitUntil(5_000) { prices().size == before + 1 }
        assertEquals(12_000_000L, prices().first().priceE5)

        // A wrong price says so and keeps the dialog; cancelling changes nothing.
        rule.onNodeWithTag("instrument-price-update").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("abc")
        rule.onNodeWithTag("instrument-price-save").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("instrument-price-dialog").assertExists()
        rule.onNodeWithTag("instrument-price-cancel").performClick()
        dialogGone()
        assertEquals(before + 1, prices().size)

        rule.onNodeWithTag("instrument-price-update").performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement("130")
        rule.onNodeWithTag("instrument-price-save").performClick()
        dialogGone()
        rule.waitUntil(5_000) { prices().first().priceE5 == 13_000_000L }
        rule.onNodeWithTag("instrument-price").assertTextContains("现价 130 CNY", substring = true)
    }
}
