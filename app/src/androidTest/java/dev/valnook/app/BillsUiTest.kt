package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveBill
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveNavigationConfiguration
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.inject.Inject

/** Recording, editing and deleting bills from the bills page, with synthetic accounts only. */
@HiltAndroidTest
class BillsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = sessions.session.value.graph
    private var cashId = 0L

    @Before fun seed() {
        hilt.inject()
        runBlocking {
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成银行", "",
                listOf(CashBalanceChange("CNY", 100_000, null, name = "合成储蓄卡")))).id
            cashId = db.cash().accountIds(account).single()
            // Bills on the bar, as a new install has them.
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveNavigationConfiguration(settings.revision, NavigationConfiguration(
                NavigationItemId.entries, setOf(NavigationItemId.BILLS, NavigationItemId.ACCOUNTS, NavigationItemId.SETTINGS))))
        }
        waitTag("nav-bills")
        rule.onNodeWithTag("nav-bills").performClick()
        waitTag("bills-list")
    }

    private fun waitTag(tag: String, timeout: Long = 8_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun keys(text: String) = text.forEach { c ->
        val name = when (c) { '+' -> "PLUS"; '-' -> "MINUS"; '*' -> "TIMES"; '/' -> "DIVIDE"; '.' -> "POINT"; else -> "D$c" }
        rule.onNodeWithTag("bill-key-$name").performClick()
    }

    /** The tag is on the field's frame; the text goes into the editable node inside it. */
    private fun input(tag: String, text: String) =
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(tag))).performTextInput(text)

    private fun balance() = runBlocking { db.cash().cashAccount(cashId)!!.balance_minor }

    @Test fun an_expense_typed_as_a_sum_is_listed_edited_and_deleted() {
        rule.onNodeWithTag("add-bill").performClick()
        waitTag("bill-editor")
        keys("35+12.5*2")
        rule.onNodeWithTag("bill-amount").assertTextContains("60.00", substring = true)
        rule.onNodeWithTag("bill-category-food").performClick()
        rule.onNodeWithTag("bill-sub-food.lunch").performClick()
        // A new bill starts on no account; the account pill opens the picker.
        rule.onNodeWithTag("bill-account").assertTextEquals("不计入账户").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-account-$cashId").performClick()
        rule.onNodeWithTag("bill-account").assertTextEquals("合成银行 · 合成储蓄卡")
        rule.onNodeWithTag("bill-confirm").assertTextEquals("=").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        waitTag("bills-list")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("−60.00 CNY").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(100_000L - 6_000, balance())

        // The category remembers the account it was last used with.
        rule.onNodeWithTag("add-bill").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-category-food").performClick()
        rule.onNodeWithTag("bill-account").assertTextEquals("合成银行 · 合成储蓄卡")
        androidx.test.espresso.Espresso.pressBack()
        waitTag("bills-list")

        // Edit: the amount goes from 60 to 45, and the balance follows. With no note the row shows "category-subcategory".
        rule.onNodeWithText("餐饮-午餐").performClick()
        waitTag("bill-edit")
        rule.onNodeWithTag("bill-edit").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-amount").performClick()
        repeat(5) { rule.onNodeWithTag("bill-key-BACKSPACE").performClick() }
        keys("45")
        rule.onNodeWithTag("bill-confirm").performClick()
        rule.waitUntil(8_000) { balance() == 100_000L - 4_500 }

        // Delete from the detail: the balance is back where it started.
        waitTag("bill-delete")
        rule.onNodeWithTag("bill-delete").performScrollTo().performClick()
        rule.onNodeWithTag("bill-delete-confirm").performClick()
        rule.waitUntil(8_000) { balance() == 100_000L }
        waitTag("bills-list")
        rule.onNodeWithText("本月还没有账单。").assertExists()
    }

    @Test fun money_sent_to_an_outside_account_names_the_other_side() {
        rule.onNodeWithTag("add-bill").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-tab-TRANSFER").performClick()
        rule.onNodeWithTag("bill-transfer-from").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-account-$cashId").performClick()
        rule.onNodeWithTag("bill-transfer-to").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-external").performClick()
        input("bill-counterparty", "合成房东")
        // Back to a figure: the system keyboard goes away and the bill keypad returns.
        rule.onNodeWithTag("bill-amount").performClick()
        waitTag("bill-keypad")
        keys("200")
        rule.onNodeWithTag("bill-confirm").performClick()
        waitTag("bills-list")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("合成银行 · 合成储蓄卡 → 合成房东").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(100_000L - 20_000, balance())
    }

    @Test fun a_new_category_with_its_own_icon_appears_on_the_form() {
        rule.onNodeWithTag("bills-categories").performClick()
        waitTag("bill-categories")
        rule.onNodeWithTag("bill-add-category").performClick()
        waitTag("bill-category-editor")
        input("bill-category-name", "合成宠物")
        rule.onNodeWithTag("bill-color-brown").performClick()
        rule.onNodeWithTag("bill-category-editor").performScrollToNode(hasTestTag("bill-icon-paw"))
        rule.onNodeWithTag("bill-icon-paw").performClick()
        rule.onNodeWithTag("bill-category-save").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("bill-category-editor").fetchSemanticsNodes().isEmpty() }
        val created = runBlocking { graph.bills!!.observeCategories().first().single { it.customName == "合成宠物" } }
        assertEquals("paw" to "brown", created.iconKey to created.colorKey)
        assertTrue(created.parentId == null)
    }

    /** Deleting a category asks what happens to its bills: moved to another category, or deleted with the balance back. */
    @Test fun a_category_with_bills_is_deleted_by_moving_or_deleting_them() {
        val categories = runBlocking { graph.bills!!.observeCategories().first() }
        fun key(value: String) = categories.single { it.builtinKey == value }.id
        fun record(sub: String, amount: Long) = runBlocking {
            graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE,
                graph.clock.millis(), amount, "CNY", 0, cashId, amount, categoryId = key("food"), subCategoryId = key(sub))))
        }
        record("food.lunch", 1_000); record("food.lunch", 500); record("food.breakfast", 300)
        assertEquals(100_000L - 1_800, balance())
        rule.onNodeWithTag("bills-categories").performClick()
        waitTag("bill-categories")
        fun open(tag: String) {
            rule.onNodeWithTag("bill-categories").performScrollToNode(hasTestTag("bill-category-line-$tag"))
            rule.onNodeWithTag("bill-category-line-$tag").performClick()
            waitTag("bill-category-editor")
        }

        // A top-level category with subcategories cannot go yet, and says why.
        open("food")
        rule.onNodeWithTag("bill-category-delete").assertIsNotEnabled()
        rule.onNodeWithTag("bill-category-delete-blocked").assertTextEquals("请先删除它的子分类，隐藏的也要删除。")
        androidx.test.espresso.Espresso.pressBack()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("bill-category-editor").fetchSemanticsNodes().isEmpty() }

        // Lunch's two bills are deleted; the balance keeps their money, as only deleting a bill on its own moves it back.
        open("food.lunch")
        rule.onNodeWithTag("bill-category-delete").performClick()
        waitTag("bill-category-delete-dialog")
        rule.onNodeWithText("这个分类下有 2 笔账单。").assertExists()
        rule.onNodeWithTag("bill-category-delete-confirm").assertIsNotEnabled()
        rule.onNodeWithTag("bill-category-delete-bills").performClick()
        rule.onNodeWithTag("bill-category-delete-confirm").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("bill-category-line-food.lunch").fetchSemanticsNodes().isEmpty() }
        assertEquals(100_000L - 1_800, balance())
        assertEquals(listOf("餐饮-午餐", "餐饮-午餐"), db.openHelper.readableDatabase.query("SELECT note FROM cash_entries WHERE source_kind='BILL_REMOVED' AND cash_account_id=?",
            arrayOf(cashId)).use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() })

        // Breakfast's bill moves to dinner and keeps its money.
        open("food.breakfast")
        rule.onNodeWithTag("bill-category-delete").performClick()
        waitTag("bill-category-delete-dialog")
        rule.onNodeWithTag("bill-category-delete-move").performClick()
        rule.onNodeWithTag("bill-category-delete-dialog").performScrollToNode(hasTestTag("bill-move-to-food.dinner"))
        rule.onNodeWithTag("bill-move-to-food.dinner").performClick()
        rule.onNodeWithTag("bill-category-delete-confirm").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("bill-category-line-food.breakfast").fetchSemanticsNodes().isEmpty() }
        val moved = runBlocking { db.bills().billsOnCategory(key("food.dinner")) }
        assertEquals(listOf(300L), moved.map { it.amount_minor })
        assertEquals(100_000L - 1_800, balance())
    }

    private fun recordLunch(amount: Long): Long = runBlocking {
        val categories = graph.bills!!.observeCategories().first()
        fun key(value: String) = categories.single { it.builtinKey == value }.id
        graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE,
            graph.clock.millis() - 60_000, amount, "CNY", 0, cashId, amount, categoryId = key("food"),
            subCategoryId = key("food.lunch")))).id
    }

    /** Part of an expense comes back as its own row, shows on the expense, and can be undone. */
    @Test fun a_partial_refund_comes_back_and_can_be_undone() {
        val lunch = recordLunch(10_000)
        rule.waitUntil(8_000) { rule.onAllNodesWithText("餐饮-午餐").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("餐饮-午餐").performClick()
        waitTag("bill-refund")
        rule.onNodeWithTag("bill-refund").performScrollTo().performClick()
        waitTag("bill-settle")
        // It starts from everything that is left, back to the account it was paid from.
        rule.onNodeWithTag("bill-settle-amount").assertTextContains("100.00", substring = true)
        rule.onNodeWithTag("bill-settle-account").assertTextEquals("合成银行 · 合成储蓄卡")
        rule.onNodeWithTag("bill-key-BACKSPACE").performTouchInput { longClick() }
        keys("30")
        rule.onNodeWithTag("bill-confirm").performClick()
        rule.waitUntil(8_000) { balance() == 100_000L - 10_000 + 3_000 }
        waitTag("bill-settlements")
        val refund = runBlocking { db.bills().children(lunch).single() }
        androidx.test.espresso.Espresso.pressBack()
        waitTag("bills-list")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("退款 · 餐饮-午餐").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("已退 30.00", substring = true).assertExists()

        rule.onNodeWithText("餐饮-午餐").performClick()
        waitTag("bill-undo-${refund.id}")
        rule.onNodeWithTag("bill-undo-${refund.id}").performScrollTo().performClick()
        rule.onNodeWithTag("bill-undo-confirm").performClick()
        rule.waitUntil(8_000) { balance() == 100_000L - 10_000 }
    }

    /** An expense to be paid back starts out of income and expense, names who pays, and is paid back in full. */
    @Test fun an_expense_to_be_paid_back_is_paid_back_and_goes_with_its_bill() {
        rule.onNodeWithTag("add-bill").performClick()
        waitTag("bill-editor")
        keys("80")
        rule.onNodeWithTag("bill-category-food").performClick()
        rule.onNodeWithTag("bill-sub-food.dinner").performClick()
        rule.onNodeWithTag("bill-account").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-account-$cashId").performClick()
        rule.onNodeWithTag("bill-reimbursable").performClick()
        rule.onNodeWithTag("bill-count-in-stats").assertTextEquals("不计入收支")
        rule.onNodeWithTag("bill-payer").performTextInput("合成公司")
        rule.onNodeWithTag("bill-amount").performClick()
        waitTag("bill-keypad")
        rule.onNodeWithTag("bill-confirm").performClick()
        waitTag("bills-list")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("待回款 80.00", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(100_000L - 8_000, balance())

        rule.onNodeWithText("餐饮-晚餐").performClick()
        waitTag("bill-reimburse")
        rule.onNodeWithText("合成公司").assertExists()
        rule.onNodeWithTag("bill-reimburse").performScrollTo().performClick()
        waitTag("bill-settle")
        rule.onNodeWithTag("bill-settle-amount").assertTextContains("80.00", substring = true)
        rule.onNodeWithTag("bill-confirm").performClick()
        rule.waitUntil(8_000) { balance() == 100_000L }
        rule.waitUntil(8_000) { rule.onAllNodesWithText("已回款").fetchSemanticsNodes().isNotEmpty() }

        // Deleting the bill takes its reimbursement along; the account ends where it started.
        rule.onNodeWithTag("bill-delete").performScrollTo().performClick()
        rule.onNodeWithText("将同时删除 1 条退款和报销回款记录，所有账户的余额一起回退。").assertExists()
        rule.onNodeWithTag("bill-delete-confirm").performClick()
        waitTag("bills-list")
        rule.waitUntil(8_000) { runBlocking { db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM bill_entries WHERE is_deleted=0").use { it.moveToFirst(); it.getInt(0) } } == 0 }
        assertEquals(100_000L, balance())
    }

    /** Settings sums up what is owed; several expenses are paid back at once, one of them in part. */
    @Test fun several_expenses_are_paid_back_at_once_from_settings() {
        val categories = runBlocking { graph.bills!!.observeCategories().first() }
        fun key(value: String) = categories.single { it.builtinKey == value }.id
        fun owed(amount: Long, payer: String) = runBlocking {
            graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE,
                graph.clock.millis() - 60_000, amount, "CNY", 0, cashId, amount, categoryId = key("business"),
                subCategoryId = key("business.travel"), countInStats = false, reimbursable = true, counterparty = payer))).id
        }
        val trip = owed(5_000, "合成公司")
        val dinner = owed(3_000, "合成朋友")
        recordLunch(1_000)
        assertEquals(100_000L - 9_000, balance())
        rule.onNodeWithTag("nav-settings").performClick()
        waitTag("settings-reimbursements")
        rule.onNodeWithTag("settings-reimbursements").assertTextContains("2 笔", substring = true)
        rule.onNodeWithTag("settings-reimbursements").performClick()
        waitTag("reimbursements")
        waitTag("reimbursement-$trip")
        rule.onNodeWithTag("reimbursement-$dinner").assertExists()
        // Only one payer's.
        rule.onNodeWithTag("reimbursements-payer-合成朋友").performClick()
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("reimbursement-$trip").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("reimbursements-payer-合成朋友").performClick()
        waitTag("reimbursement-$trip")

        rule.onNodeWithTag("reimbursements-select").performClick()
        rule.onNodeWithTag("reimbursement-$trip").performClick()
        rule.onNodeWithTag("reimbursement-$dinner").performClick()
        rule.onNodeWithTag("reimbursements-batch-next").performClick()
        waitTag("reimbursements-batch")
        rule.onNodeWithTag("reimbursements-batch-account").assertTextEquals("合成银行 · 合成储蓄卡")
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("reimbursements-batch-amount-$dinner"))).performTextReplacement("10")
        rule.onNodeWithTag("reimbursements-batch-confirm").performClick()
        rule.waitUntil(8_000) { balance() == 100_000L - 9_000 + 5_000 + 1_000 }
        val states = runBlocking { listOf(trip, dinner).map { db.bills().bill(it)!!.reimburse_state } }
        assertEquals(listOf("DONE", "PARTIAL"), states)
        // Back on the list, only what is still owed is left.
        waitTag("reimbursements")
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("reimbursement-$trip").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("reimbursement-$dinner").assertExists()
    }
}
