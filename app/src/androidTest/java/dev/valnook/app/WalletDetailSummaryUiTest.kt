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

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)

    private fun create(change: CashBalanceChange) = runBlocking {
        graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "Example bank", "", listOf(change)))
        graph.overview.snapshot().cash.single()
    }

    /** Opens a card bound to [cashId] straight into its detail. */
    private fun show(cashId: Long, dark: Boolean = false) {
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
            WalletScreen(vm, {}, { _, _, _ -> }, { parent, cash -> edits += parent to cash }, walletCashSlots)
        } } }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("wallet-cash-summary").fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(10_000) { vm.trend.value.isNotEmpty() }
        rule.waitForIdle()
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
        rule.onNodeWithTag("cash-info-note", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-limit-card").assertDoesNotExist()
        rule.onNodeWithTag("cash-billing-tag", useUnmergedTree = true).assertDoesNotExist()

        // The details card is a little deeper than the panel around it.
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        val card = bounds("cash-info-card")
        val inside = image.getPixel((card.left + 6).toInt(), card.center.y.toInt())
        val around = image.getPixel((card.left + 6).toInt(), (card.top - 6).toInt())
        assertTrue("Card ${Integer.toHexString(inside)} is deeper than panel ${Integer.toHexString(around)}",
            luminance(inside) < luminance(around) - 3)
        image.recycle()
        save("savings-light")

        edit.performClick()
        assertEquals(listOf(cash.account_id to cash.id), edits)
    }

    @Test fun credit_shows_its_state_bill_limit_and_details_cards() {
        val cash = create(CashBalanceChange("CNY", -775_449, null, name = "Visa card", type = BalanceAccountType.CREDIT,
            credit = CreditAccountInput(5_000_000, 7, CreditDueRule.AfterStatementDays(20), null)))
        show(cash.id, dark = true)
        rule.onNodeWithText(string(CashR.string.cash_amount_owed), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-billing-tag", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-limit-card").assertExists()
        rule.onNodeWithTag("cash-info-statement", useUnmergedTree = true).assertExists()
        // The old lines of small text are gone: the statement day reads only in the details card.
        rule.onAllNodesWithText(string(CashR.string.cash_statement_day, 7), useUnmergedTree = true).assertCountEquals(0)
        assertTrue(bounds("cash-limit-card").top > bounds("wallet-cash-balance").bottom)
        save("credit-dark")
    }
}
