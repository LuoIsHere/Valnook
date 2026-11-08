package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.navigation.walletCashSlots
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SetCashBalance
import dev.valnook.domain.money.DecimalRules
import dev.valnook.designsystem.localMonthLabel
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import dev.valnook.feature.cash.R as CashR
import dev.valnook.feature.wallet.WalletImageCodec
import dev.valnook.feature.wallet.WalletScreen
import dev.valnook.feature.wallet.WalletViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** The wallet card's sub-account reads like the sub-account page: edit beside the bank, sparkline beside the balance. */
@HiltAndroidTest
class WalletDetailSummaryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val graph get() = rule.activity.sessions.session.value.graph
    private val wallet get() = requireNotNull(graph.wallet)
    private val edits = mutableListOf<Pair<Long, Long>>()
    private val opened = mutableListOf<Long>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun create(change: CashBalanceChange) = runBlocking {
        graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "Example bank", "", listOf(change)))
        graph.overview.snapshot().cash.single()
    }

    /** Opens a card bound to [cashId] straight into its detail. */
    private fun show(cashId: Long, dark: Boolean = false): WalletViewModel {
        val card = runBlocking {
            val bitmap = Bitmap.createBitmap(1000, 631, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xffe0874f.toInt())
            val image = WalletImageCodec.encode(bitmap, 1f, 0f, 0f); bitmap.recycle()
            wallet.save(null, null, "Card", cashId, image.key, image)
        }
        val vm = rule.runOnUiThread { WalletViewModel(graph.sessionId, wallet, graph.overview, graph.cashPages, graph.clock,
            SavedStateHandle(), null, graph.walletPrivate, graph.cash) }
        rule.waitUntil(10_000) { vm.cards.value != null }
        rule.runOnUiThread { vm.select(card) }
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme = dark) {
            WalletScreen(vm, {}, { _, _, id -> opened += id }, { parent, cash -> edits += parent to cash }, walletCashSlots)
        } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("wallet-cash-summary").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { vm.trend.value.isNotEmpty() && !vm.ledger.value.loading }
        rule.waitForIdle()
        return vm
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun textBounds(text: String): Rect = rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("wallet-summary-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun luminance(pixel: Int) = 0.2126 * android.graphics.Color.red(pixel) +
        0.7152 * android.graphics.Color.green(pixel) + 0.0722 * android.graphics.Color.blue(pixel)

    @Test fun savings_puts_edit_beside_the_bank_and_the_sparkline_beside_the_balance() {
        val cash = create(CashBalanceChange("CNY", 22_558_498, null, name = "Everyday savings", note = "Salary card"))
        show(cash.id)
        val edit = rule.onNodeWithContentDescription(string(CashR.string.cash_edit_balance))
        val editBox = edit.fetchSemanticsNode().boundsInRoot
        val bank = textBounds("Example bank")
        val balance = bounds("wallet-cash-balance")
        assertEquals("The edit button shares the bank's line", bank.center.y, editBox.center.y, 2f)
        assertTrue("…above the name and balance", editBox.bottom <= textBounds("Everyday savings").top + 1)
        val spark = bounds("cash-sparkline-${cash.id}")
        assertTrue("The sparkline is right of the balance", spark.left >= balance.right - 1)
        assertTrue("…on its line", spark.top < balance.bottom && spark.bottom > balance.top)
        // The note is plain text under the balance; with nothing else to say there is no details card.
        val note = bounds("wallet-cash-note")
        assertTrue("The note sits under the balance", note.top >= balance.bottom - 1 && note.left <= balance.left + 1)
        rule.onNodeWithText("Salary card").assertExists()
        rule.onNodeWithTag("cash-info-card").assertDoesNotExist()
        rule.onNodeWithTag("cash-limit-card").assertDoesNotExist()
        rule.onNodeWithTag("cash-billing-tag", useUnmergedTree = true).assertDoesNotExist()
        save("savings-light")

        edit.performClick()
        assertEquals(listOf(cash.account_id to cash.id), edits)
    }

    @Test fun credit_shows_its_state_bill_limit_and_details_cards() {
        val cash = create(CashBalanceChange("CNY", -775_449, null, name = "Visa card", type = BalanceAccountType.CREDIT,
            credit = CreditAccountInput(5_000_000, 7, CreditDueRule.AfterStatementDays(20), null), note = "Travel only"))
        show(cash.id, dark = true)
        rule.onNodeWithText(string(dev.valnook.core.designsystem.R.string.credit_owed), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-billing-tag", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-limit-card").assertExists()
        rule.onNodeWithTag("cash-info-statement", useUnmergedTree = true).assertExists()
        // The old lines of small text are gone: the statement day reads only in the details card.
        rule.onAllNodesWithText(string(CashR.string.cash_statement_day, 7), useUnmergedTree = true).assertCountEquals(0)
        assertTrue(bounds("cash-limit-card").top > bounds("wallet-cash-note").bottom)
        assertTrue(bounds("wallet-cash-note").top >= bounds("wallet-cash-balance").bottom - 1)
        // The note is said once, under the balance, not again in the details card.
        rule.onNodeWithTag("cash-info-note", useUnmergedTree = true).assertDoesNotExist()
        rule.onAllNodesWithText("Travel only").assertCountEquals(1)

        // The details card is a little deeper than the panel around it.
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val card = bounds("cash-info-card")
        val inside = image.getPixel((card.left + 6).toInt(), card.center.y.toInt())
        val around = image.getPixel((card.left + 6).toInt(), (card.top - 6).toInt())
        assertTrue("Card ${Integer.toHexString(inside)} is deeper than panel ${Integer.toHexString(around)}",
            luminance(inside) < luminance(around) - 3)
        image.recycle()
        save("credit-dark")
    }

    @Test fun changes_sit_in_one_card_per_day_with_the_balance_after_each() {
        val cash = create(CashBalanceChange("CNY", 100_000, null, name = "Everyday savings"))
        listOf(120_000L to "Salary", 115_000L to "Lunch").forEach { (balance, note) ->
            val current = runBlocking { graph.cash.observe_cash(cash.account_id).first().single() }
            runBlocking { graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(), cash.account_id, "CNY", balance,
                current.revision, current.id, current.name, entryNote = note)) }
        }
        val vm = show(cash.id)
        val entries = runBlocking { graph.cash.observeCashEntries(cash.id, 10).first() }
            .sortedWith(compareByDescending<dev.valnook.domain.model.CashEntry> { it.occurred_at_ms }.thenByDescending { it.id })
        assertEquals(3, entries.size)
        val date = Instant.ofEpochMilli(entries.first().occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()
        val locale = rule.activity.resources.configuration.locales[0]
        rule.onNodeWithTag("wallet-month").assertTextEquals(localMonthLabel(vm.ledger.value.month, locale))
        // The title and the month share one line.
        val header = bounds("wallet-changes-header")
        assertTrue(bounds("wallet-month").center.y in header.top..header.bottom)

        rule.onNodeWithTag("wallet-ledger").performScrollToKey("day-$date")
        val day = hasAnyAncestor(hasTestTag("cash-day-$date"))
        entries.forEach { rule.onNodeWithTag("cash-entry-${it.id}").assert(day) }
        // Newest first; under each change the balance it left.
        assertTrue(bounds("cash-entry-${entries[0].id}").top < bounds("cash-entry-${entries[1].id}").top)
        listOf(115_000L, 120_000L, 100_000L).forEachIndexed { index, after ->
            rule.onNodeWithTag("cash-entry-balance-${entries[index].id}", useUnmergedTree = true)
                .assertTextEquals(DecimalRules.format_display(after, 2))
        }
        rule.onNodeWithTag("cash-day-$date").performScrollTo()
        save("ledger-light")
        rule.onNodeWithTag("cash-entry-${entries[1].id}").performClick()
        assertEquals(listOf(entries[1].id), opened)

        rule.runOnUiThread { vm.month(vm.ledger.value.month.minusMonths(1)) }
        rule.waitUntil(10_000) { !vm.ledger.value.loading }
        rule.onNodeWithTag("wallet-ledger").performScrollToKey("empty")
        rule.onNodeWithTag("cash-month-empty").assertExists()
        rule.onNodeWithTag("cash-day-$date").assertDoesNotExist()
    }
}
