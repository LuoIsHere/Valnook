package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.*
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.accounts.AccountDeletionDialog
import dev.valnook.feature.cash.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.UUID

@HiltAndroidTest
class AccountDeletionUiTest {
    @get:Rule(order=0) val hilt=HiltAndroidRule(this)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    @Before fun prepare(){hilt.inject()}
    private fun scene(content: @Composable () -> Unit){rule.runOnUiThread{rule.activity.setContent(content=content)};rule.waitForIdle()}
    private fun image(name:String){
        val node=if(rule.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()) rule.onNode(isDialog()) else rule.onRoot()
        val bitmap=node.captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun confirmation_rejects_wrong_code_and_deletes_only_after_correct_code_in_dark_large_text(){
        val graph=rule.activity.sessions.session.value.graph
        val parent=runBlocking{graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"Bank","",listOf(CashBalanceChange("CNY",-12345,null,name="Card")))).id}
        val cash=runBlocking{graph.overview.snapshot().cash.single{it.account_id==parent}}
        var preview:AccountDeletionPreview?=null;var deleted=false
        val commands=object:FinancialCommands by graph.commands{
            override suspend fun previewAccountDeletion(accountId:Long,balanceAccountId:Long?) = graph.commands.previewAccountDeletion(accountId,balanceAccountId).also{preview=it}
        }
        scene {val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)){ValnookTheme(dark_theme=true){
                if(!deleted)AccountDeletionDialog(commands,parent,cash.id,{}, {deleted=true})
            }}
        }
        rule.waitUntil(10000){preview!=null && rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("account-delete-confirm").assertIsNotEnabled()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextInput("wrong")
        rule.onNodeWithTag("account-delete-confirm").assertIsNotEnabled()
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement(preview!!.code)
        rule.onNodeWithTag("account-delete-confirm").assertIsEnabled()
        image("account-delete-dark")
        rule.onNodeWithTag("account-delete-confirm").performClick()
        rule.waitUntil(10000){deleted}
        assertTrue(runBlocking{graph.overview.snapshot().cash.none{it.id==cash.id}})
        assertTrue(runBlocking{graph.overview.snapshot().accounts.any{it.id==parent}})
    }
    @Test fun cancel_keeps_data_and_main_deletion_preview_lists_credit_transfers(){
        var cancelled=false;var calls=0
        val p=AccountDeletionPreview("ticket","314159",1,2,"Credit card",1,"CNY",-12345,1,12,2,3,
            listOf(CreditLimitTransfer("Visa","CNY",2000000),CreditLimitTransfer("Mastercard","CNY",2000000)))
        val commands=object:FinancialCommands {
            override suspend fun previewAccountDeletion(accountId:Long,balanceAccountId:Long?)=p
            override suspend fun execute(command:FinancialCommand):OperationResult{calls++;return OperationResult("BALANCE_ACCOUNT",2)}
        }
        scene { ValnookTheme { AccountDeletionDialog(commands,1,2,{cancelled=true},{}) } }
        rule.waitUntil{rule.onAllNodesWithText("Visa",substring=true).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Mastercard",substring=true).assertExists()
        image("account-delete-light")
        rule.onNodeWithText(rule.activity.getString(dev.valnook.core.designsystem.R.string.cancel)).performClick()
        assertTrue(cancelled);assertEquals(0,calls)
    }
    @Test fun main_account_toolbar_deletes_and_returns_to_accounts_without_stale_routes(){
        val graph=rule.activity.sessions.session.value.graph
        val parent=runBlocking{graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"Delete parent","",listOf(CashBalanceChange("CNY",10000,null,name="Cash")))).id}
        rule.waitUntil(10000){rule.onAllNodesWithTag("account-total-$parent").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithTag("account-total-$parent").performClick()
        val edit=rule.activity.getString(dev.valnook.app.R.string.nav_edit)
        rule.waitUntil(10000){rule.onAllNodesWithText(edit).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText(edit).performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("account-delete-open").fetchSemanticsNodes().any{!it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)}}
        rule.onNodeWithTag("account-delete-open").performClick()
        val prefix=rule.activity.getString(dev.valnook.feature.accounts.R.string.account_delete_code,"")
        try { rule.waitUntil(10000){rule.onAllNodesWithText(prefix,substring=true).fetchSemanticsNodes().isNotEmpty()} }
        catch(failure:Throwable) {
            image("account-delete-navigation-failure")
            PlatformTestStorageRegistry.getInstance().openOutputFile("account-delete-navigation-tree.txt").use {
                it.write(rule.onAllNodes(isRoot()).printToString().toByteArray())
            }
            throw failure
        }
        val text=rule.onNodeWithText(prefix,substring=true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString{it.text}
        val code=Regex("[0-9]{6}").find(text)!!.value
        rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performScrollTo().performTextInput(code)
        rule.onNodeWithTag("account-delete-confirm").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithTag("accounts-list").fetchSemanticsNodes().isNotEmpty()}
        assertTrue(runBlocking{graph.overview.snapshot().accounts.none{it.id==parent}})
        rule.onNodeWithTag("account-delete-open").assertDoesNotExist()
    }

    @Test fun cash_month_arrows_show_business_month_and_keep_balance_unchanged(){
        val graph=rule.activity.sessions.session.value.graph
        val parent=runBlocking{graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"Bank","",listOf(CashBalanceChange("CNY",12345,null,name="Cash")))).id}
        val cash=runBlocking{graph.overview.snapshot().cash.single{it.account_id==parent}}
        val clock=Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"),ZoneId.of("Asia/Hong_Kong"))
        val vm=rule.runOnUiThread{CashViewModel(parent,graph.cash,graph.cashPages,SavedStateHandle(),graph.overview,clock)}
        scene {ValnookTheme {Box(Modifier.width(360.dp)){CashDetail(vm,cash.id,{},{})}}}
        rule.waitUntil(10000){vm.entries.value is CashLedgerState.Ready}
        rule.onNodeWithText("2026-10").assertExists()
        rule.onNodeWithTag("month-previous").performClick()
        rule.waitUntil{(vm.entries.value as? CashLedgerState.Ready)?.month==YearMonth.of(2026,9)}
        assertTrue((vm.entries.value as CashLedgerState.Ready).rows.isEmpty())
        assertEquals(12345L,(vm.entries.value as CashLedgerState.Ready).account.balance_minor)
        rule.onNodeWithTag("month-next").performClick()
        rule.waitUntil{(vm.entries.value as? CashLedgerState.Ready)?.month==YearMonth.of(2026,10)}
        image("account-month-light")
    }
}
