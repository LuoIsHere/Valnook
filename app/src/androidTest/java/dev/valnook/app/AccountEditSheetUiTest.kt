package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BalanceAccountChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.feature.accounts.*
import dev.valnook.feature.accounts.R as AccountsR
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

/** The account edit page lists subaccounts; each one is edited in its own panel. */
@HiltAndroidTest
class AccountEditSheetUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject() }

    private val graph get() = rule.activity.sessions.session.value.graph
    private fun string(id: Int) = rule.activity.getString(id)

    private fun draft(accountId: Long? = null): AccountEditViewModel {
        val vm = rule.runOnUiThread { AccountEditViewModel(accountId, graph.overview, graph.commands, SavedStateHandle()) }
        rule.waitUntil(10_000) { vm.state.value.loaded }
        return vm
    }

    private fun edit(vm: AccountEditViewModel, fontScale: Float? = null) {
        rule.runOnUiThread { rule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale ?: density.fontScale)) {
                ValnookTheme {
                    var toolbar by remember { mutableStateOf<AccountEditToolbarState?>(null) }
                    Column(Modifier.fillMaxSize()) { AccountEditSaveAction(toolbar); AccountEditScreen(vm, {}, onSaveActionChanged = { toolbar = it }) }
                }
            }
        } }
        rule.waitForIdle()
    }

    private fun sheetOpen() = rule.onAllNodesWithTag("subaccount-sheet").fetchSemanticsNodes().isNotEmpty()

    private fun closeSheet() {
        rule.onNodeWithTag("subaccount-sheet-done").performClick()
        rule.waitUntil(5_000) { !sheetOpen() }
    }

    @Test fun a_new_subaccount_opens_in_a_panel_and_keeps_what_was_typed_after_closing() {
        val vm = draft()
        edit(vm)
        rule.onNodeWithTag("account-add-row").performClick()
        rule.waitUntil(5_000) { sheetOpen() }
        rule.onNodeWithText(string(AccountsR.string.account_balance_name)).performTextReplacement("Daily card")
        rule.onNodeWithText(string(AccountsR.string.account_balance)).performTextReplacement("12.5")
        closeSheet()
        val row = vm.state.value.rows.single()
        assertEquals("Daily card", row.nameInput)
        rule.onNodeWithTag("subaccount-row-${row.key}").assert(hasText("Daily card", substring = true))
            .assert(hasText("12.50 CNY", substring = true)).performClick()
        rule.waitUntil(5_000) { sheetOpen() }
        rule.onNodeWithTag("input-${string(AccountsR.string.account_balance_name)}").assert(hasText("Daily card"))
        rule.onNodeWithTag("subaccount-cancel").performClick()
        rule.waitUntil(5_000) { !sheetOpen() }
        assertTrue(vm.state.value.rows.isEmpty())
    }

    @Test fun a_failed_save_opens_the_subaccount_that_needs_fixing() {
        val vm = draft()
        rule.runOnUiThread { vm.changeName("Bank"); vm.addRow(); vm.addRow() }
        val second = vm.state.value.rows[1].key
        rule.runOnUiThread { vm.changeRow(vm.state.value.rows[0].key, name = "Fine") }
        edit(vm)
        assertFalse(sheetOpen())
        rule.onNodeWithTag("account-edit-save").performClick()
        rule.waitUntil(5_000) { sheetOpen() }
        rule.onNodeWithTag("input-${string(AccountsR.string.account_balance_name)}")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        closeSheet()
        rule.onNodeWithTag("subaccount-row-$second").assert(hasText(rule.activity.resources
            .getQuantityString(AccountsR.plurals.account_issue_count, 1, 1), substring = true))
    }

    @Test fun a_saved_subaccount_keeps_its_type_and_currency() {
        runBlocking {
            graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "Bank", "", listOf(
                BalanceAccountChange("CNY", -500, null, name = "Card", type = BalanceAccountType.CREDIT,
                    credit = CreditAccountInput(500000, 7, CreditDueRule.AfterStatementDays(20), null)))))
        }
        val account = runBlocking { graph.overview.snapshot().accounts.single { it.name == "Bank" } }
        val vm = draft(account.id)
        edit(vm)
        rule.onNodeWithText(string(AccountsR.string.account_credit_owed)).assertExists()
        rule.onNodeWithText("Card").performClick()
        rule.waitUntil(5_000) { sheetOpen() }
        rule.onNode(hasText(string(AccountsR.string.account_type_savings)) and isSelectable()).assertIsNotEnabled()
        rule.onNodeWithContentDescription("${string(dev.valnook.core.designsystem.R.string.currency)}: CNY").assertIsNotEnabled()
        rule.onNodeWithText(string(AccountsR.string.account_credit_limit)).assertExists()
    }

    @Test fun large_text_puts_currency_above_balance() {
        val vm = draft()
        rule.runOnUiThread { vm.addRow() }
        edit(vm, fontScale = 2f)
        rule.onNodeWithTag("subaccount-row-${vm.state.value.rows.single().key}").performScrollTo().performClick()
        rule.waitUntil(5_000) { sheetOpen() }
        val currency = rule.onNodeWithTag("input-${string(dev.valnook.core.designsystem.R.string.currency)}").getUnclippedBoundsInRoot()
        val balance = rule.onNodeWithTag("input-${string(AccountsR.string.account_balance)}").getUnclippedBoundsInRoot()
        assertTrue("Balance sits below currency", balance.top >= currency.bottom)
    }
}
