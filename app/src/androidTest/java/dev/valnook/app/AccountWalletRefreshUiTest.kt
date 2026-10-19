package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.*
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.cash.*
import dev.valnook.feature.investments.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@HiltAndroidTest
class AccountWalletRefreshUiTest {
    @get:Rule(order=0)val hilt=HiltAndroidRule(this)
    @get:Rule(order=1)val rule=createAndroidComposeRule<MainActivity>()
    private val graph get()=rule.activity.sessions.session.value.graph
    @Before fun prepare(){hilt.inject();rule.waitForIdle()}
    private fun label(id:Int)=rule.activity.getString(id)
    private fun waitTag(tag:String){rule.waitUntil(15000){rule.onAllNodesWithTag(tag,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    private fun capture(name:String) {
        rule.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()!!
        PlatformTestStorageRegistry.getInstance().openOutputFile("refresh-$name.png").use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun root_balance_editor_saves_note_and_returns_to_live_chart() {
        val parent=runBlocking {graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"Design account","",emptyList())).id}
        val cash=runBlocking {
            graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(),parent,"CNY",438898,null,name="Everyday balance"))
            graph.cash.observe_cash(parent).first().single()
        }
        waitTag("account-total-$parent")
        rule.onNodeWithTag("account-total-$parent").performClick()
        waitTag("account-section-ACCOUNTS")
        rule.onNodeWithText("Everyday balance").performClick()
        rule.waitUntil(10000){rule.onAllNodesWithContentDescription(label(dev.valnook.feature.cash.R.string.cash_edit_balance)).fetchSemanticsNodes().isNotEmpty()}
        capture("cash-detail")
        rule.onNodeWithText("近6个月").assertDoesNotExist()
        rule.onNodeWithContentDescription(label(dev.valnook.feature.cash.R.string.cash_edit_balance)).performClick()
        waitTag("cash-balance-input")
        rule.onNodeWithTag("cash-balance-input").performTextReplacement("4500.00")
        rule.onNodeWithTag("cash-balance-note").performTextInput("Reconciled balance")
        capture("balance-editor")
        rule.onNodeWithTag("cash-balance-save").assertIsEnabled().performClick()
        rule.waitUntil(15000){rule.onAllNodesWithTag("cash-balance-input").fetchSemanticsNodes().isEmpty()}
        runBlocking {
            assertEquals(450000L,graph.cash.observeCashAccount(cash.id).first()!!.balance_minor)
            assertTrue(graph.cash.observeCashEntries(cash.id,10).first().any{it.note=="Reconciled balance"&&it.delta_minor==11102L})
        }
        rule.onAllNodesWithTag("root-toolbar").assertCountEquals(1)
        capture("cash-saved")
    }
    @Test fun built_in_front_editor_persists_through_session_wrapper() {
        val wallet=requireNotNull(graph.wallet)
        val id=runBlocking{wallet.save(null,null,"Geometry card",null,null,faceStyle=WalletFaceStyle())}
        rule.onNodeWithTag("nav-wallet").performClick();waitTag("wallet-card-$id")
        rule.onNodeWithTag("wallet-card-$id",useUnmergedTree=true).performClick();waitTag("wallet-edit")
        rule.onNodeWithTag("wallet-edit").performClick();waitTag("wallet-name")
        val zh=rule.activity.resources.configuration.locales[0].language=="zh"
        // Option rows are lazy: off-screen chips are not composed until the row itself scrolls to them.
        // 选项行是 LazyRow：屏幕外的选项尚未组合，必须先让该行滚动到目标项。
        fun chooseFace(row:String,label:String) {
            rule.onNodeWithTag(row).performScrollTo().performScrollToNode(hasText(label))
            rule.onNodeWithText(label).performClick()
        }
        chooseFace("wallet-face-background",if(zh)"柔光"else"Glow")
        chooseFace("wallet-face-geometry",if(zh)"方块"else"Blocks")
        capture("wallet-style-editor")
        rule.onNodeWithTag("wallet-save").performScrollTo().performClick()
        rule.waitUntil(15000){rule.onAllNodesWithTag("wallet-save").fetchSemanticsNodes().isEmpty()}
        runBlocking { val card=wallet.observeCards().first().single()
            assertEquals(WalletFaceBackground.RADIAL,card.faceStyle!!.background)
            assertEquals(WalletFaceGeometry.BLOCKS,card.faceStyle!!.geometry);assertNull(card.imageKey)
        }
        capture("wallet-style-saved")
    }
    @Test fun closed_positions_are_scoped_to_parent_and_exclude_untraded_positions() {
        val snapshot=runBlocking{graph.overview.snapshot()}
        fun position(id:Long,parent:Long,name:String,closed:Boolean)=Investment(id,parent,1,"Type",name,"TEST",Currency.of("USD"),0,100000000,0,isClosed=closed)
        val fixture=snapshot.copy(positions=listOf(position(1,10,"This account closed",true),position(2,20,"Other account closed",true),position(3,10,"Never traded",false)))
        val repository=object:OverviewRepository {
            override fun observeSnapshot()=flowOf(fixture)
            override suspend fun snapshot()=fixture
        }
        val vm=PortfolioViewModel(repository);var opened=0L
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme=true) { Surface(Modifier.safeDrawingPadding()) { AccountInvestments(vm,10,true,{opened=it},{},{}) } } } }
        rule.waitUntil(10000){rule.onAllNodesWithText("This account closed").fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText("Other account closed").assertDoesNotExist();rule.onNodeWithText("Never traded").assertDoesNotExist()
        capture("closed-positions-dark")
        rule.onNodeWithText("This account closed").performClick();assertEquals(1L,opened)
    }
    @Test fun deposit_actions_share_top_row_and_large_text_cash_remains_readable() {
        val open=label(dev.valnook.core.designsystem.R.string.open_deposit)
        val settled=label(dev.valnook.core.designsystem.R.string.settled_deposits)
        rule.runOnUiThread {rule.activity.setContent {ValnookTheme {Surface(Modifier.safeDrawingPadding()) {
            dev.valnook.feature.deposits.DepositsContent(emptyList(),0,{},{},on_archive={})
        }}}}
        rule.onAllNodesWithText(open).assertCountEquals(1)
        val left=rule.onNodeWithText(open).fetchSemanticsNode().boundsInRoot
        val right=rule.onNodeWithText(settled).fetchSemanticsNode().boundsInRoot
        assertEquals(left.center.y,right.center.y,4f);capture("deposit-actions")
        rule.runOnUiThread{rule.activity.setContent {ValnookTheme(dark_theme=true) {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)){Surface(Modifier.safeDrawingPadding()) {
                CashContent(listOf(CashAccount(1,Currency.of("CNY"),438898,1,1,"Long subaccount name for layout verification")),on_open={})
            }}
        }}}
        rule.onNodeWithText("4 388.98 CNY").assertExists();capture("cash-large-text-dark")
    }
    @Test fun bundled_demo_shows_real_daily_balances() {
        val enter=rule.runOnUiThread { rule.activity.lifecycleScope.async {rule.activity.sessions.enterDemo()} }
        rule.waitUntil(20000){enter.isCompleted};runBlocking{enter.await()};rule.waitForIdle()
        val snapshot=runBlocking{graph.overview.snapshot()}
        val savings=snapshot.cash.first{it.creditProfile==null}
        val credit=snapshot.cash.first{it.account_id==savings.account_id&&it.creditProfile!=null}
        val points=runBlocking{graph.cash.observeTrends(savings.account_id,graph.clock.instant(),graph.clock.zone).first()}
        assertTrue(points.getValue(savings.id).count{it.balanceMinor!=null} >= 90)
        assertEquals(java.math.BigInteger.valueOf(savings.balance_minor),points.getValue(savings.id).last().balanceMinor)
        waitTag("account-total-${savings.account_id}")
        rule.onNodeWithTag("account-total-${savings.account_id}").performScrollTo().performClick()
        rule.waitUntil(15000){rule.onAllNodesWithText(savings.name).fetchSemanticsNodes().isNotEmpty()}
        capture("demo-subaccounts")
        rule.onNodeWithText(savings.name).performClick();rule.waitForIdle()
        rule.waitUntil(15000){rule.onAllNodes(hasContentDescription(points.getValue(savings.id).first().date.toString(),substring=true)).fetchSemanticsNodes().isNotEmpty()}
        capture("demo-savings-trend")
        rule.onNodeWithContentDescription(label(dev.valnook.app.R.string.nav_back)).performClick()
        rule.onNodeWithText(credit.name).performScrollTo().performClick();rule.waitForIdle()
        rule.waitUntil(15000){rule.onAllNodes(hasContentDescription(points.getValue(credit.id).first().date.toString(),substring=true)).fetchSemanticsNodes().isNotEmpty()}
        rule.onNodeWithText(label(dev.valnook.feature.cash.R.string.cash_amount_owed)).assertExists()
        capture("demo-debt-trend")
        rule.onNodeWithText("近6个月").assertDoesNotExist()
        rule.onNodeWithText("余额趋势").assertDoesNotExist()
    }
}
