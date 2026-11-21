package dev.valnook.app

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveBill
import dev.valnook.feature.wallet.WalletImageCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.inject.Inject

/** Coming back to a card's detail from a page opened on it never shows the wallet's own bar for a moment. */
@HiltAndroidTest
class WalletBackNavigationUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = rule.activity.sessions.session.value.graph
    private var card = 0L
    private var entry = 0L

    private fun shown(tag: String) = rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    @Before fun seed() {
        hilt.inject()
        rule.waitForIdle()
        runBlocking {
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "Synthetic bank", "",
                listOf(CashBalanceChange("CNY", 100_000, null, name = "Synthetic debit")))).id
            val cashId = db.cash().accountIds(account).single()
            val categories = graph.bills!!.observeCategories().first()
            fun key(value: String) = categories.single { it.builtinKey == value }.id
            val bill = graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE,
                graph.clock.millis() - 60_000, 3_000, "CNY", 0, cashId, 3_000, categoryId = key("food"),
                subCategoryId = key("food.lunch")))).id
            entry = db.bills().legs(bill).single().id
            val bitmap = Bitmap.createBitmap(1000, 631, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff4f87e0.toInt())
            val image = WalletImageCodec.encode(bitmap, 1f, 0f, 0f); bitmap.recycle()
            card = requireNotNull(graph.wallet).save(null, null, "Synthetic card", cashId, image.key, image)
        }
        rule.waitUntil(10_000) { shown("nav-wallet") }
        rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithTag("wallet-card-$card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun returning_to_a_card_keeps_its_detail_bar_from_the_first_frame() {
        rule.onNodeWithTag("wallet-card-$card", useUnmergedTree = true).performClick()
        rule.waitUntil(10_000) { shown("cash-entry-$entry") }
        rule.waitForIdle()
        assertTrue("the card detail hides the navigation bar", !shown("nav-wallet"))
        rule.onNodeWithTag("cash-entry-$entry").performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithText("查看账单").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        // The entry page names itself in the bar rather than falling back to the app name.
        rule.onNode(hasText("流水详情") and hasAnyAncestor(hasTestTag("root-toolbar")), useUnmergedTree = true).assertExists()

        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        val frames = (0 until 30).map { rule.mainClock.advanceTimeByFrame(); shown("nav-wallet") }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertTrue("navigation bar shown in frames ${frames.withIndex().filter { it.value }.map { it.index }}",
            frames.none { it })
        assertTrue(shown("cash-entry-$entry") && !shown("nav-wallet"))
    }
}
