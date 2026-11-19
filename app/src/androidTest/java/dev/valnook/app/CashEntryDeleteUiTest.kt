package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.model.Currency
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SetCashBalance
import dev.valnook.feature.cash.CashEntryDetailContent
import dev.valnook.feature.cash.CashEntryDetailScreen
import dev.valnook.feature.cash.CashEntryViewModel
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** A hand-made balance change can be deleted from its detail page; entries from other records cannot. */
@HiltAndroidTest
class CashEntryDeleteUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val graph get() = rule.activity.sessions.session.value.graph

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }
    @After fun close() { rule.runOnUiThread { store.clear() } }

    @Test fun deleting_a_balance_adjustment_moves_the_balance_back_and_leaves() {
        val parent = runBlocking { graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "Example bank", "",
            listOf(CashBalanceChange("CNY", 100_000, null, name = "Everyday savings")))).id }
        val cash = runBlocking { graph.cash.observe_cash(parent).first().single() }
        runBlocking { graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(), parent, "CNY", 120_000, cash.revision,
            cash.id, cash.name, entryNote = "Synthetic adjustment")) }
        val raise = runBlocking { graph.cash.observeCashEntries(cash.id, 10).first().single { it.delta_minor == 20_000L } }
        var left = 0
        rule.runOnUiThread {
            val vm = CashEntryViewModel(cash.id, raise.id, graph.cash, graph.commands)
            store.put("entry", vm)
            rule.activity.setContent {
                ValnookTheme { Surface(Modifier.fillMaxSize()) { CashEntryDetailScreen(vm, "Example bank", { left++ }) {} } }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("cash-entry-delete").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("cash-entry-delete").performScrollTo().performClick()
        rule.onNodeWithText("删除后余额 -200.00 CNY。").assertExists()
        rule.onNodeWithTag("cash-entry-delete-confirm").performClick()
        rule.waitUntil(10_000) { left == 1 }
        assertEquals(100_000L, runBlocking { graph.cash.observe_cash(parent).first().single().balance_minor })
        assertNull(runBlocking { graph.cash.observeCashEntry(cash.id, raise.id).first() })
        // The page leaves rather than saying the record is gone.
        rule.onNodeWithText("这条流水已删除", substring = true).assertDoesNotExist()
    }

    @Test fun an_entry_from_a_trade_has_no_delete_button() {
        val entry = CashEntry(1, 1, Currency.of("CNY"), -10_000, System.currentTimeMillis(), CashSource.TRADE, 7, 3,
            "", 1)
        rule.runOnUiThread {
            rule.activity.setContent {
                ValnookTheme { Surface(Modifier.fillMaxSize()) {
                    CashEntryDetailContent(entry, "Example bank", on_delete = { error("not offered") }, on_edit = {})
                } }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("cash-entry-delete").assertDoesNotExist()
    }
}
