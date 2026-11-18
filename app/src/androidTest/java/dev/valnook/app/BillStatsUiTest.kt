package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveBill
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.domain.repository.SaveNavigationConfiguration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/** The statistics page's expense and income tabs, with synthetic bills only. */
@HiltAndroidTest
class BillStatsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = sessions.session.value.graph
    private var cashId = 0L

    private fun waitTag(tag: String, timeout: Long = 8_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    /** While a tab changes, both pages are briefly there: an exact match picks the right one. */
    private fun waitText(tag: String, text: String, substring: Boolean = false) = rule.waitUntil(8_000) {
        rule.onAllNodes(hasTestTag(tag) and hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }

    private fun category(key: String) = runBlocking { graph.bills!!.observeCategories().first() }.single { it.builtinKey == key }.id

    private fun save(type: BillType, at: Long, amount: Long, key: String, discount: Long = 0, note: String = "") = runBlocking {
        graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(type, at, amount, "CNY",
            discount, cashId, amount - discount, categoryId = category(key), note = note))).id
    }

    @Before fun seed() {
        hilt.inject()
        runBlocking {
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveFinancialSettings(settings.revision, Currency.of("CNY"), emptyList()))
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成银行", "",
                listOf(CashBalanceChange("CNY", 1_000_000, null, name = "合成储蓄卡")))).id
            cashId = db.cash().accountIds(account).single()
            val shown = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveNavigationConfiguration(shown.revision, NavigationConfiguration(
                NavigationItemId.entries, setOf(NavigationItemId.BILLS, NavigationItemId.STATISTICS, NavigationItemId.SETTINGS))))
        }
        val now = graph.clock.millis() - 60_000
        val lastMonth = Instant.ofEpochMilli(now).atZone(graph.clock.zone).minusMonths(1).toInstant().toEpochMilli()
        // This month: food 30 (3 off) + 18, transport 12; pay 900. Last month: food 50.
        save(BillType.EXPENSE, now, 3_300, "food", discount = 300, note = "合成午饭")
        save(BillType.EXPENSE, now, 1_800, "food", note = "合成晚饭")
        save(BillType.EXPENSE, now, 1_200, "transport", note = "合成地铁")
        save(BillType.INCOME, now, 90_000, "income.pay", note = "合成工资")
        save(BillType.EXPENSE, lastMonth, 5_000, "food", note = "合成上月")
        waitTag("nav-statistics")
        rule.onNodeWithTag("nav-statistics").performClick()
        waitTag("statistics-tabs")
    }

    @Test fun the_expense_tab_shows_the_month_against_the_one_before() {
        // The page opens on the assets, as it always did.
        rule.onNodeWithTag("statistics-list").assertExists()
        rule.onNodeWithTag("statistics-tab-expense").performClick()
        waitText("bill-stats-total", "60.00 CNY")
        waitTag("bill-stats-expense")
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("statistics-list").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("bill-stats-other").assertTextContains("收入 900.00 CNY · 结余 840.00 CNY", substring = true)
        rule.onNodeWithTag("bill-stats-compare").assertTextEquals("比上月多 10.00 CNY（20.0%）")
        rule.onNodeWithTag("bill-stats-savings").assertTextEquals("优惠共省 3.00 CNY")
        rule.onNodeWithTag("bill-stats-partial").assertDoesNotExist()
        // Largest first: food 48 in two bills, then transport 12.
        rule.onNodeWithTag("bill-stats-ring").assertExists()
        val food = category("food")
        val transport = category("transport")
        rule.onNodeWithTag("bill-stats-category-$food").assertTextContains("2 笔 · 80.0%", substring = true)
            .assertTextContains("48.00", substring = true)
        rule.onNodeWithTag("bill-stats-category-$transport").assertTextContains("1 笔 · 20.0%", substring = true)
        val rows = rule.onAllNodes(hasTestTag("bill-stats-category-$food") or hasTestTag("bill-stats-category-$transport"))
            .fetchSemanticsNodes().map { it.boundsInRoot.top }
        assert(rows[0] < rows[1]) { "food should come before transport" }

        // A day's bar tells what was spent that day; by default the line gives the daily average.
        // To the end of the page, so the bars sit above the navigation bar rather than under it.
        repeat(3) { rule.onNodeWithTag("bill-stats-expense").performTouchInput { swipeUp() } }
        rule.waitForIdle()
        rule.onNodeWithTag("bill-stats-readout").assertTextContains("日均", substring = true)
        val today = java.time.LocalDate.now(graph.clock)
        rule.onNodeWithTag("bill-stats-bars").performTouchInput {
            val slot = width.toFloat() / today.lengthOfMonth()
            click(androidx.compose.ui.geometry.Offset(slot * (today.dayOfMonth - 0.5f), height / 2f))
        }
        waitText("bill-stats-readout", "60.00 CNY", substring = true)
        rule.waitUntil(8_000) {
            rule.onAllNodes(hasTestTag("bill-stats-readout") and hasText("日均", substring = true)).fetchSemanticsNodes().isEmpty()
        }
        // Tapped again, the day lets go.
        rule.onNodeWithTag("bill-stats-bars").performTouchInput {
            val slot = width.toFloat() / today.lengthOfMonth()
            click(androidx.compose.ui.geometry.Offset(slot * (today.dayOfMonth - 0.5f), height / 2f))
        }
        waitText("bill-stats-readout", "日均", substring = true)
    }

    @Test fun expense_and_income_share_the_month() {
        rule.onNodeWithTag("statistics-tab-expense").performClick()
        waitText("bill-stats-total", "60.00 CNY")
        rule.onNodeWithTag("bill-stats-summary").performScrollTo()
        rule.onNodeWithTag("month-previous").performClick()
        // Last month: food 50, against nothing the month before.
        waitText("bill-stats-total", "50.00 CNY")
        rule.onNodeWithTag("bill-stats-compare").assertTextEquals("比上月多 50.00 CNY")
        rule.onNodeWithTag("bill-stats-savings").assertDoesNotExist()
        // The income tab stays on that month, which had none.
        rule.onNodeWithTag("statistics-tab-income").performClick()
        waitTag("bill-stats-income")
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("bill-stats-expense").fetchSemanticsNodes().isEmpty() }
        waitText("bill-stats-total", "0.00 CNY")
        rule.onNodeWithTag("bill-stats-empty").assertTextEquals("这个月没有收入")
        rule.onNodeWithTag("month-next").performClick()
        waitText("bill-stats-total", "900.00 CNY")
        rule.onNodeWithTag("bill-stats-other").assertTextContains("支出 60.00 CNY · 结余 840.00 CNY", substring = true)
        rule.onNodeWithTag("bill-stats-category-${category("income.pay")}").assertTextContains("1 笔 · 100.0%", substring = true)
        // Back on the assets, the tabs keep working.
        rule.onNodeWithTag("statistics-tab-assets").performClick()
        waitTag("statistics-list")
    }
}
