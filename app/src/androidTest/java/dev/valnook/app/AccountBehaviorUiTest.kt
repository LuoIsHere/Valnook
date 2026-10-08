package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.*
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.feature.accounts.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

@HiltAndroidTest
class AccountBehaviorUiTest {
    @get:Rule(order=0) val hilt=HiltAndroidRule(this)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject() }
    private fun scene(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent(content=content) };rule.waitForIdle()
    }
    private fun image(name:String) {
        val bitmap=rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
    @Test fun first_and_middle_rows_move_one_slot_and_remain_stable_after_release() {
        var order=(1..8).map { ReorderItem(it.toString(),"Account $it") }
        scene { ValnookTheme {
            var items by remember { mutableStateOf(order) }
            ReorderList(items, { items=it.map { key -> items.single { row -> row.key==key } };order=items }, "Reorder", "Move up", "Move down", Modifier.fillMaxWidth().height(580.dp))
        } }
        fun move(key:String,next:String) {
            val from=rule.onNodeWithTag("sort-row-$key").fetchSemanticsNode().boundsInRoot.center.y
            val to=rule.onNodeWithTag("sort-row-$next").fetchSemanticsNode().boundsInRoot.center.y
            rule.onNodeWithTag("sort-handle-$key").performTouchInput { swipe(center,center+Offset(0f,(to-from)*1.15f),500) }
            rule.waitForIdle()
        }
        move("1","2")
        assertEquals(listOf("2","1","3","4","5","6","7","8"),order.map { it.key })
        move("3","4")
        assertEquals(listOf("2","1","4","3","5","6","7","8"),order.map { it.key })
        image("v0012-account-sort")
    }
    @Test fun toolbar_save_marks_invalid_fields_and_accepts_zero_credit_without_currency_name() {
        val graph=rule.activity.sessions.session.value.graph
        val vm=rule.runOnUiThread { AccountEditViewModel(null,graph.overview,graph.commands,SavedStateHandle()) }
        rule.waitUntil(10000) { vm.state.value.loaded }
        rule.runOnUiThread {
            vm.changeName("Test bank");vm.addRow()
            vm.changeRow(vm.state.value.rows.single().key,type=BalanceAccountType.CREDIT,creditLimit="-1")
        }
        assertEquals("",vm.state.value.rows.single().nameInput)
        scene {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.3f)) {
                ValnookTheme(dark_theme=true) {
                    var toolbar by remember { mutableStateOf<AccountEditToolbarState?>(null) }
                    Column(Modifier.width(360.dp).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { AccountEditSaveAction(toolbar) }
                        AccountEditScreen(vm,{},onSaveActionChanged={toolbar=it})
                    }
                }
            }
        }
        rule.onNodeWithTag("account-edit-save").performClick()
        rule.waitUntil { vm.state.value.fieldErrors.size>=2 }
        assertTrue(rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error)).fetchSemanticsNodes().isNotEmpty())
        image("v0012-account-invalid-dark")
        rule.runOnUiThread {
            val key=vm.state.value.rows.single().key
            vm.changeRow(key,name="Credit card",creditLimit="0",balance="-12.34",showOnAccountsPage=false)
            vm.changeVisibility(deposits=false)
        }
        rule.onNodeWithTag("account-edit-save").performClick()
        rule.waitUntil(10000) { vm.submission.value.phase==dev.valnook.domain.command.SubmissionPhase.SUCCEEDED }
        val snapshot=runBlocking { graph.overview.snapshot() }
        assertEquals(0L,snapshot.cash.single().creditProfile!!.creditLimitMinor)
        assertFalse(snapshot.cash.single().showOnAccountsPage)
        assertFalse(snapshot.accounts.single().showDepositSummary)
    }
    @Test fun instrument_search_selects_from_catalogue_without_creating_a_position() {
        val graph=rule.activity.sessions.session.value.graph
        runBlocking {
            val type=graph.commands.execute(dev.valnook.domain.repository.SaveAssetType(java.util.UUID.randomUUID().toString(),null,"Stocks")).id
            graph.commands.execute(dev.valnook.domain.repository.SaveInstrument(java.util.UUID.randomUUID().toString(),null,null,"Apple","AAPL",type,"USD",12345000))
            graph.commands.execute(dev.valnook.domain.repository.SaveInstrument(java.util.UUID.randomUUID().toString(),null,null,"Tencent","00700",type,"HKD",45000000))
        }
        val vm=rule.runOnUiThread { dev.valnook.feature.investments.PositionCreateViewModel(graph.instruments,SavedStateHandle()) }
        var selected:Long?=null
        scene { ValnookTheme { dev.valnook.feature.investments.PositionCreateForm(vm,{selected=it},{}) } }
        rule.waitUntil { rule.onAllNodesWithText("Apple").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.investments.R.string.investment_search)).performTextInput("aapl")
        rule.onNodeWithText("Tencent").assertDoesNotExist()
        rule.onNodeWithText("Apple").performClick()
        assertNotNull(selected)
        assertTrue(runBlocking { graph.overview.snapshot().positions.isEmpty() })
        image("v0012-instrument-search")
    }
    @Test fun zero_limit_indicator_and_compact_balance_control_render_in_both_themes() {
        for(dark in listOf(false,true)) {
            var clicked=false
            scene { ValnookTheme(dark_theme=dark) {
                Column(Modifier.width(320.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.Text("Credit · 0.00 CNY")
                    CreditLimitProgress(0.0,0.0)
                    CreditLimitProgress(12.34,0.0)
                    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.Text("123.45 CNY",Modifier.weight(1f),style=androidx.compose.material3.MaterialTheme.typography.headlineSmall)
                        CompactEditButton("Edit balance") {clicked=true}
                    }
                }
            } }
            rule.onNodeWithContentDescription("Edit balance").assertHeightIsEqualTo(32.dp).performClick()
            assertTrue(clicked)
            rule.mainClock.advanceTimeBy(500)
            image("v0012-credit-"+if(dark) "dark" else "light")
        }
    }
    @Test fun hidden_subaccounts_do_not_hide_detail_data_or_change_total() {
        val cny=Currency.of("CNY")
        val snapshot=AssetSnapshot(listOf(SavingsAccount(1,"Bank","",showDepositSummary=false,showInvestmentSummary=false)),
            listOf(CashAccount(1,cny,10000,1,id=1,name="Visible cash"),CashAccount(1,cny,20000,1,id=2,name="Reserve",includeInAvailableCash=false,showOnAccountsPage=false)),
            emptyList(),emptyList(),emptyList(),AppSettings(baseCurrency=cny))
        scene { ValnookTheme(dark_theme=false) { AccountsContent(AssetValuation.calculate(snapshot),{},snapshot) } }
        rule.onNodeWithText("Bank").performClick()
        rule.onNodeWithTag("account-cash-1").assertExists()
        rule.onNodeWithTag("account-cash-2").assertDoesNotExist()
        rule.onAllNodesWithText("100.00 CNY",substring=true).assertCountEquals(3)
        rule.mainClock.advanceTimeBy(500)
        image("v0012-accounts-light")
    }
}
