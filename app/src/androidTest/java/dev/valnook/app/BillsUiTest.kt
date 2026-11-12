package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.repository.CashBalanceChange
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
        rule.onNodeWithTag("bill-confirm").assertTextEquals("=").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        waitTag("bills-list")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("−60.00 CNY").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(100_000L - 6_000, balance())

        // Edit: the amount goes from 60 to 45, and the balance follows.
        rule.onNodeWithText("午餐").performClick()
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
        rule.onNodeWithTag("bill-categories").performScrollToNode(hasTestTag("bill-add-category"))
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
}
