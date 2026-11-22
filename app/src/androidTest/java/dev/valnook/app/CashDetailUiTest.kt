package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.LocalPageTopBarHeight
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.feature.cash.CashDetail
import dev.valnook.feature.cash.CashViewModel
import java.time.Clock
import java.time.Instant
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

/** The sub-account page's header: what it says above the balance, its edit button, and how it collapses. */
@HiltAndroidTest
class CashDetailUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private val graph get() = rule.activity.sessions.session.value.graph
    private var edits = 0
    private var opened = 0L
    private var vmClock = clock
    private val bar = 56.dp
    private val width = 360.dp

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }
    @After fun close() { rule.runOnUiThread { store.clear() } }

    private val card = CreditAccountInput(5_000_000, 7, CreditDueRule.AfterStatementDays(20), null)
    private fun creditChange(limit: Long? = 5_000_000) = CashBalanceChange("CNY", -775_449, null, name = "Visa card",
        type = BalanceAccountType.CREDIT, credit = card.copy(creditLimitMinor = limit))
    private fun savingsChange(note: String = "") = CashBalanceChange("CNY", 22_558_498, null, name = "Everyday savings", note = note)
    private fun create(change: CashBalanceChange) = runBlocking { graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),
        null, null, "Example bank", "", listOf(change))).id }

    private fun show(credit: Boolean, dark: Boolean = false, fontScale: Float = 1f) =
        show(create(if (credit) creditChange() else savingsChange()), dark = dark, fontScale = fontScale)

    /** Shows [cashName] (or the only sub-account) of [parent]. */
    private fun show(parent: Long, cashName: String? = null, dark: Boolean = false, fontScale: Float = 1f) {
        val cash = runBlocking { graph.cash.observe_cash(parent).first().let { rows ->
            if (cashName == null) rows.single() else rows.single { it.name == cashName } } }
        rule.runOnUiThread {
            val vm = CashViewModel(parent, graph.cash, graph.cashPages, SavedStateHandle(), graph.overview, vmClock)
            store.put("cash", vm)
            rule.activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
                val context = LocalContext.current.createConfigurationContext(config)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, fontScale), LocalPageTopBarHeight provides bar) {
                    ValnookTheme(dark_theme = dark) { Surface(Modifier.fillMaxSize()) { Box {
                        // Room above the page stands in for the title bar, so the balance's final place stays on screen.
                        Box(Modifier.padding(top = bar).width(width).height(640.dp).testTag("cash-detail-shot")) {
                            CashDetail(vm, cash.id, { edits++ }, { opened = it })
                        }
                    } } }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("cash-trend-value").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        if (!TestShots.enabled) { rule.waitForIdle(); return }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("cash-detail-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun px(value: androidx.compose.ui.unit.Dp) = with(rule.density) { value.toPx() }
    private fun label() = rule.onNodeWithTag("cash-trend-date")
    private fun editButtons() = rule.onAllNodesWithContentDescription("编辑余额")

    private fun collapse() {
        val height = bounds("cash-detail-header").height
        rule.onNodeWithTag("cash-detail-header").performTouchInput {
            down(center)
            // Slow enough that releasing counts as stopping, not as a fling.
            repeat(10) { moveBy(Offset(0f, -height * .09f), delayMillis = 60) }
            advanceEventTime(500)
            moveBy(Offset.Zero)
            up()
        }
        rule.waitForIdle()
    }

    /**
     * A synthetic card (statement on the 7th, made now) with bills on both sides of 7 June 2027, seen on 11 June 2027:
     * the page's clock runs ahead, so the card already existed at the last statement.
     */
    private fun cycleCard(): Long {
        val parent = create(creditChange())
        val card = runBlocking { graph.cash.observe_cash(parent).first().single().id }
        vmClock = Clock.fixed(Instant.parse("2027-06-11T04:00:00Z"), clock.zone)
        val categories = runBlocking { graph.bills!!.observeCategories().first() }
        fun category(key: String) = categories.first { it.builtinKey == key }.id
        fun at(date: String) = java.time.LocalDateTime.parse(date).atZone(clock.zone).toInstant().toEpochMilli()
        fun expense(date: String, amount: Long) = dev.valnook.domain.model.BillDraft(dev.valnook.domain.model.BillType.EXPENSE,
            at(date), amount, "CNY", 0, card, amount, categoryId = category("food"), subCategoryId = category("food.lunch"),
            note = "Synthetic lunch")
        fun save(draft: dev.valnook.domain.model.BillDraft) = runBlocking {
            graph.commands.execute(dev.valnook.domain.repository.SaveBill(UUID.randomUUID().toString(), null, null, draft)).id }
        save(expense("2027-05-20T12:00", 30_000))
        val lunch = save(expense("2027-06-09T12:00", 12_000))
        runBlocking { graph.commands.execute(dev.valnook.domain.repository.SettleBill(UUID.randomUUID().toString(), lunch, 1,
            dev.valnook.domain.model.BillType.REFUND, dev.valnook.domain.model.SettlementDraft(at("2027-06-10T09:00"), 2_000, card, 2_000))) }
        save(dev.valnook.domain.model.BillDraft(dev.valnook.domain.model.BillType.TRANSFER, at("2027-06-10T10:00"), 50_000, "CNY", 0,
            null, 50_000, transferKind = dev.valnook.domain.model.TransferKind.FROM_EXTERNAL, toCashAccountId = card,
            toAmountMinor = 50_000))
        return parent
    }

    @Test fun credit_card_reads_its_billing_cycles_and_pages_changes_by_cycle() {
        show(cycleCard())
        rule.onNodeWithTag("cash-detail-list").performScrollToNode(hasTestTag("cash-cycle-card"))
        rule.onNodeWithTag("cash-cycle-current").assertTextContains("本期 · 6/8–7/7", substring = true)
        // 120.00 spent less a 20.00 refund; 500.00 paid in.
        rule.onNodeWithTag("cash-cycle-spent").assertTextContains("100.00", substring = true)
        rule.onNodeWithTag("cash-cycle-refunded").assertTextContains("20.00", substring = true)
        rule.onNodeWithTag("cash-cycle-repaid").assertTextContains("500.00", substring = true)
        rule.onNodeWithTag("cash-cycle-previous").assertTextContains("上期 · 5/8–6/7", substring = true)
        rule.onNodeWithTag("cash-cycle-paid").assertTextContains("500.00", substring = true)
        save("cycle-card")

        // Changes default to this cycle, with its totals before refunds.
        rule.onNodeWithTag("cash-detail-list").performScrollToNode(hasTestTag("cash-cycle-totals"))
        rule.onNodeWithTag("ledger-month").assertTextEquals("6/8–7/7（本期）")
        rule.onNodeWithTag("cash-cycle-totals-spent").assertTextContains("120.00", substring = true)
        rule.onNodeWithTag("cash-view-cycle").assertIsSelected()
        save("cycle-changes")

        // The last statement from the card: its own changes, scrolled to their heading.
        rule.onNodeWithTag("cash-detail-list").performScrollToNode(hasTestTag("cash-cycle-previous"))
        rule.onNodeWithTag("cash-cycle-previous").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("5/8–6/7", substring = true).fetchSemanticsNodes().size >= 2 }
        rule.onNodeWithTag("ledger-month").assertTextEquals("5/8–6/7")
        rule.onNodeWithTag("cash-cycle-totals-spent").assertTextContains("300.00", substring = true)

        // By month: the calendar month, without cycle totals.
        rule.onNodeWithTag("cash-view-month").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("cash-cycle-totals").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("cash-view-month").assertIsSelected()
        save("month-changes")
    }

    @Test fun credit_header_shows_the_debt_and_bill_then_collapses_into_the_bar() {
        show(credit = true)
        label().assertTextContains("欠款", substring = true)
        rule.onNodeWithTag("cash-billing-tag", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-trend-value").assertTextEquals("-7 754.49 CNY")
        save("credit-open")

        editButtons().assertCountEquals(1)
        editButtons()[0].performClick()
        rule.runOnIdle { assertEquals(1, edits) }

        val open = bounds("cash-trend-value")
        collapse()
        val closed = bounds("cash-trend-value")
        assertTrue("Smaller: ${open.width} -> ${closed.width}", closed.width < open.width * .7f)
        assertEquals("Centered in the title bar", px(bar) / 2f, closed.center.y, 3f)
        assertEquals("Ends before the delete button", px(width) - px(60.dp), closed.right, 3f)
        // Folded away with the chart: nothing to reach until the header opens again.
        editButtons().assertCountEquals(0)
        save("credit-closed")
    }

    @Test fun savings_header_says_current_balance_and_a_touch_shows_the_day() {
        show(credit = false)
        label().assertTextEquals("当前余额")
        rule.onAllNodesWithTag("cash-billing-tag", useUnmergedTree = true).assertCountEquals(0)
        val chart = rule.onNodeWithTag("cash-trend-chart")
        chart.performTouchInput { down(Offset(width * .3f, height * .5f)) }
        rule.waitForIdle()
        assertTrue("A date replaces the label",
            rule.onNodeWithTag("cash-trend-date").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString().contains("年"))
        chart.performTouchInput { up() }
        rule.waitForIdle()
        label().assertTextEquals("当前余额")
        save("savings-light")
    }

    @Test fun credit_cards_show_the_limit_and_statement_date_once() {
        show(credit = true)
        rule.onNodeWithTag("cash-limit-card").assertExists()
        listOf("7 754.49", "42 245.51", "50 000.00").forEach {
            rule.onNode(hasText(it) and hasAnyAncestor(hasTestTag("cash-limit-card"))).assertExists()
        }
        rule.onNodeWithTag("cash-info-statement").assertTextContains("每月 7 日", substring = true)
        rule.onAllNodesWithTag("cash-info-source").assertCountEquals(0)
        rule.onAllNodesWithTag("cash-info-note").assertCountEquals(0)
        rule.onAllNodesWithTag("cash-over-limit").assertCountEquals(0)
        // The header says it; the limit and details cards no longer repeat the debt or the bill. (The billing-cycle
        // card has its own: what the last statement owed and when each cycle closes or is due.)
        val outsideCycles = !hasAnyAncestor(hasTestTag("cash-cycle-card"))
        rule.onAllNodes(hasText("欠款", substring = true) and outsideCycles).assertCountEquals(1)
        // Every statement or due reminder ends in 天 (days / today); only the header's tag says one.
        rule.onAllNodes(hasText("天", substring = true) and outsideCycles).assertCountEquals(1)
        rule.onAllNodes(hasText("账单日：", substring = true)).assertCountEquals(0)
    }

    @Test fun a_shared_card_names_its_limit_source_and_says_when_over_the_limit() {
        val parent = create(creditChange(limit = 500_000))
        val root = runBlocking { graph.cash.observe_cash(parent).first().single() }
        val account = runBlocking { graph.overview.snapshot().accounts.single { it.id == parent } }
        runBlocking { graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), parent, account.revision, account.name, "",
            listOf(CashBalanceChange("CNY", root.balance_minor, root.revision, root.id, root.name, type = BalanceAccountType.CREDIT,
                credit = card.copy(creditLimitMinor = 500_000)),
                CashBalanceChange("CNY", -10_000, null, name = "Family card", type = BalanceAccountType.CREDIT,
                credit = card.copy(creditLimitMinor = null, limitSourceAccountId = root.id))))) }
        show(parent, "Family card")
        rule.onNodeWithTag("cash-info-source").assertTextContains("Visa card", substring = true)
        rule.onNodeWithTag("cash-over-limit").assertExists()
        save("credit-shared-over-limit")
    }

    @Test fun savings_shows_only_its_note_and_nothing_without_one() {
        show(create(savingsChange(note = "Daily spending")))
        rule.onAllNodesWithTag("cash-limit-card").assertCountEquals(0)
        rule.onNodeWithTag("cash-info-note").assertTextContains("Daily spending", substring = true)
        rule.onAllNodesWithTag("cash-info-statement").assertCountEquals(0)
    }

    @Test fun savings_without_a_note_has_no_cards() {
        show(credit = false)
        rule.onAllNodesWithTag("cash-limit-card").assertCountEquals(0)
        rule.onAllNodesWithTag("cash-info-card").assertCountEquals(0)
    }

    private fun septemberAt(day: Int, hour: Int) =
        java.time.LocalDateTime.of(2026, 9, day, hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A savings account with 1 000.00 on 1 September, then +200.00 and -50.00 on the 3rd. */
    private fun septemberLedger(): Long {
        val parent = create(CashBalanceChange("CNY", 100_000, null, name = "Everyday savings"))
        fun cash() = runBlocking { graph.cash.observe_cash(parent).first().single() }
        listOf(120_000L to "Salary", 115_000L to "Lunch").forEach { (balance, note) ->
            val current = cash()
            runBlocking { graph.commands.execute(dev.valnook.domain.repository.SetCashBalance(UUID.randomUUID().toString(), parent,
                "CNY", balance, current.revision, current.id, current.name, entryNote = note)) }
        }
        val id = cash().id
        // Oldest first, so each record lands at its own time in order.
        val times = listOf(septemberAt(1, 12), septemberAt(3, 9), septemberAt(3, 18))
        runBlocking { graph.cash.observeCashEntries(id, 10).first().sortedBy { it.id } }.forEachIndexed { index, entry ->
            val fresh = runBlocking { graph.cash.observeCashEntry(id, entry.id).first()!! }
            runBlocking { graph.commands.execute(dev.valnook.domain.repository.EditCashEntry(UUID.randomUUID().toString(),
                entry.id, fresh.revision, fresh.delta_minor, times[index], fresh.note)) }
        }
        vmClock = Clock.fixed(Instant.ofEpochMilli(septemberAt(15, 12)), ZoneId.systemDefault())
        return parent
    }

    @Test fun changes_group_by_day_in_one_card_each_with_the_balance_after() {
        show(septemberLedger())
        rule.onNodeWithTag("ledger-month").assertTextEquals("2026年9月")
        rule.onNodeWithTag("cash-day-2026-09-03").assertExists()
        rule.onNodeWithTag("cash-day-2026-09-01").assertExists()
        val third = hasAnyAncestor(hasTestTag("cash-day-2026-09-03"))
        // Newest first within the day: lunch left 1 150.00, the salary before it 1 200.00.
        val lunch = rule.onNode(hasText("Lunch") and third).fetchSemanticsNode().boundsInRoot
        val salary = rule.onNode(hasText("Salary") and third).fetchSemanticsNode().boundsInRoot
        assertTrue("Lunch is above salary", lunch.top < salary.top)
        rule.onNode(hasText("-50.00 CNY") and third).assertExists()
        rule.onNode(hasText("+200.00 CNY") and third).assertExists()
        rule.onNode(hasText("1 150.00") and third, useUnmergedTree = true).assertExists()
        rule.onNode(hasText("1 200.00") and third, useUnmergedTree = true).assertExists()
        rule.onNode(hasText("1 000.00") and hasAnyAncestor(hasTestTag("cash-day-2026-09-01")), useUnmergedTree = true).assertExists()
        rule.onAllNodes(hasContentDescription("变化后余额 1 150.00"), useUnmergedTree = true).assertCountEquals(1)
        save("ledger-september")

        rule.onNode(hasText("Lunch") and third).performClick()
        rule.runOnIdle { assertTrue(opened != 0L) }
    }

    @Test fun a_month_without_changes_says_so_in_a_card() {
        show(septemberLedger())
        rule.onNodeWithTag("month-next").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("cash-month-empty").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ledger-month").assertTextEquals("2026年10月")
        rule.onNodeWithText("本月没有余额变化").assertIsDisplayed()
    }
}
