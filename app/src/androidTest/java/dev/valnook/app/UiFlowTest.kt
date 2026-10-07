package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.Modifier
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.activity.compose.setContent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.*
import org.junit.Assert.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.designsystem.*
import dev.valnook.feature.accounts.AccountsContent
import dev.valnook.feature.cash.CashContent
import dev.valnook.feature.cash.CashEntryDetailContent
import dev.valnook.feature.deposits.DepositsContent
import dev.valnook.feature.deposits.DepositDetailContent
import dev.valnook.feature.investments.HoldingRow
import dev.valnook.feature.investments.TradeDetailContent
import java.io.File
import java.time.LocalDate
import java.util.UUID
import android.app.LocaleManager
import android.graphics.Bitmap
import android.os.LocaleList
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher

@HiltAndroidTest
class UiFlowTest {
    @get:Rule(order=0) val hilt=HiltAndroidRule(this)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions:AppSessionManager
    private val graph get()=sessions.session.value.graph
    @Inject lateinit var db:ValnookDatabase
    private var account_id=0L
    @Before fun seed(){hilt.inject();runBlocking{
        account_id=createAccount("合成账户 A","仅测试数据")
        createAccount("合成账户 B","账户隔离")
    };rule.runOnUiThread {assertFalse(rule.activity.window.isNavigationBarContrastEnforced)}}
    private suspend fun createAccount(name:String,note:String="")=graph.commands.execute(
        SaveAccount(UUID.randomUUID().toString(),null,null,name,note,emptyList())).id
    private suspend fun createType(name:String)=graph.commands.execute(
        SaveAssetType(UUID.randomUUID().toString(),null,name)).id
    private suspend fun createInvestment(accountId:Long,name:String,symbol:String,typeId:Long,
        currencyCode:String,quantityE8:Long,currentPriceE8:Long,costPriceE8:Long)=run {
        val instrumentId=graph.commands.execute(SaveInstrument(UUID.randomUUID().toString(),null,null,
            name,symbol,typeId,currencyCode,currentPriceE8/1_000L)).id
        val positionId=graph.commands.execute(CreateInvestmentPosition(UUID.randomUUID().toString(),
            accountId,instrumentId)).id
        if(quantityE8>0) graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(),
            positionId,Direction.BUY,quantityE8,costPriceE8,Long.MIN_VALUE,false))
        positionId
    }
    private fun wait_text(text:String) {
        try {
            rule.waitUntil(15000){rule.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
            rule.mainClock.advanceTimeBy(200)
            rule.waitForIdle()
        }
        catch(error:androidx.compose.ui.test.ComposeTimeoutException) {
            PlatformTestStorageRegistry.getInstance().openOutputFile("timeout-tree.txt").use{it.write(rule.onRoot().printToString().toByteArray())}
            shot("timeout-state")
            throw error
        }
    }
    private fun localized(resourceId: Int): String = rule.activity.getString(resourceId)
    private fun save(label:String="保存"){
        val focused=rule.onAllNodes(androidx.compose.ui.test.isFocused() and hasSetTextAction())
        if(focused.fetchSemanticsNodes().isNotEmpty())focused[0].performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun native_picker(date:Boolean) {
        onView(isAssignableFrom(if(date)DatePicker::class.java else TimePicker::class.java)).perform(object:ViewAction {
            override fun getConstraints():Matcher<View> = isDisplayed()
            override fun getDescription()="Choose native date or time"
            override fun perform(controller:UiController,view:View) {
                if(date)(view as DatePicker).updateDate(2026,8,1)
                else (view as TimePicker).apply{hour=15;minute=30}
                controller.loopMainThreadUntilIdle()
            }
        })
        val bitmap=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile(if(date)"native-date.png" else "native-time.png").use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        onView(withId(android.R.id.button1)).perform(click())
    }
    private fun click_list(text:String) {
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performClick()
    }
    private fun click_record(tag:String) {
        rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasTestTag(tag))
        rule.onNodeWithTag(tag).performClick()
    }
    private fun show_scene(name:String,content:@Composable ()->Unit) {
        rule.runOnUiThread {rule.activity.setContent {key(name){content()}}}
    }
    private fun shot(name:String,tag:String?=null,expected_width:Int?=null) {
        rule.waitForIdle()
        val node=if(tag==null)rule.onRoot() else rule.onNodeWithTag(tag)
        var captured:Bitmap?=null
        repeat(3) {attempt->
            if(captured==null) {
                rule.waitForIdle()
                try {captured=node.captureToImage().asAndroidBitmap()}
                catch(error:AssertionError) {
                    if(error.message!="Failed waiting for PixelCopy!" || attempt==2)throw error
                }
            }
        }
        val bitmap=requireNotNull(captured)
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        assertTrue(bitmap.width>0&&bitmap.height>0)
        if(expected_width!=null)assertEquals(expected_width,bitmap.width)
    }
    private fun window_shot(name:String,bottom_action:String?=null) {
        val action=bottom_action?.let{rule.onNodeWithText(it).performScrollTo().assertIsDisplayed()}
        rule.waitForIdle()
        rule.waitUntil(5000){rule.runOnUiThread {
            ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())!=true
        }}
        // Platform IME/dialog animations are not driven by Compose's test clock.
        android.os.SystemClock.sleep(300)
        val nav_bottom=rule.runOnUiThread {
            ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        }
        val bitmap=requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            if(action!=null) {
                val actionBottom=action.fetchSemanticsNode().boundsInWindow.bottom
                assertTrue("Action overlaps system navigation: actionBottom=$actionBottom safeBottom=${bitmap.height-nav_bottom} navBottom=$nav_bottom",
                    actionBottom<=bitmap.height-nav_bottom)
            }
        }finally{bitmap.recycle()}
    }
    @Test fun account_navigation_cash_errors_and_rotation_restoration() {
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performScrollTo().performClick()
        rule.onNodeWithTag("root-capsule").assertDoesNotExist()
        listOf("账户", "定期", "投资").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("编辑").performClick()
        wait_text("编辑账户")
        rule.onNodeWithText("＋ 添加账户").performScrollTo().performClick()
        rule.onNodeWithText("余额").performScrollTo().performTextInput("-1.00")
        rule.activityRule.scenario.recreate()
        wait_text("-1.00")
        save()
        wait_text("-1.00 CNY")
        runBlocking { assertEquals(-100L, db.cash().cash_one(account_id, "CNY")!!.balance_minor) }
        rule.onNodeWithText("编辑").performClick()
        rule.onNodeWithText("余额").performScrollTo().performTextReplacement("20000.00")
        save()
        wait_text("20 000.00")
        shot("cash-saved")
        runBlocking { assertEquals(2000000L, db.cash().cash_one(account_id, "CNY")!!.balance_minor) }
    }

    @Test fun settlement_requires_explicit_cash_checkbox_and_returns_once() {
        runBlocking{
            graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(), account_id,
                "CNY", 0, null, name = "结算现金"))
            graph.commands.execute(OpenTermDeposit(UUID.randomUUID().toString(),account_id,"CNY",1000000,
                R.parse_e8("3"),LocalDate.parse("2026-01-01").toEpochDay(),LocalDate.parse("2026-04-01").toEpochDay(),false))
        }
        wait_text("合成账户 A");rule.onNodeWithTag("account-total-$account_id").performClick()
        rule.onNodeWithText("定期").performClick();wait_text("已到期，待结算");shot("deposit-matured")
        rule.onNodeWithText("结束存单").assertDoesNotExist()
        rule.onNodeWithText("修改存单记录").assertDoesNotExist()
        click_record("deposit-record-1");wait_text("存单详情");shot("deposit-record-detail")
        click_list("修改存单记录")
        rule.onNodeWithText("年利率（%）").performTextReplacement("6")
        save();wait_text("存单详情");wait_text("147.95 CNY")
        rule.activityRule.scenario.recreate();wait_text("存单详情");wait_text("147.95 CNY")
        click_list("结束存单")
        rule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        shot("deposit-link-confirmation")
        save();wait_text("存单详情");wait_text("已结束")
        rule.onNodeWithText("结束存单").assertDoesNotExist()
        rule.onNodeWithContentDescription("返回").performClick();wait_text("已结算")
        rule.onNodeWithText("已结束").assertDoesNotExist()
        rule.onNodeWithText("已结算").performClick();wait_text("已结束");shot("settled-deposits")
        runBlocking{assertEquals(1014795,db.ledger().cash_one(account_id,"CNY")!!.balance_minor)}
        rule.onNodeWithText("结束存单").assertDoesNotExist()
        rule.onNodeWithText("修改存单记录").assertDoesNotExist()
        click_record("deposit-record-1");wait_text("存单详情")
        click_list("修改存单记录")
        rule.onNodeWithText("本金").performTextReplacement("20000")
        save();wait_text("存单详情");wait_text("20 000.00 CNY");wait_text("295.89 CNY")
        window_shot("settled-deposit-detail-window","修改存单记录")
        runBlocking{assertEquals(2029589,db.ledger().cash_one(account_id,"CNY")!!.balance_minor)}
        rule.onNodeWithContentDescription("返回").performClick();wait_text("已结算")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithTag("account-section-ACCOUNTS").performClick();wait_text("CNY")
        rule.onNodeWithContentDescription("余额变化 · 结算现金").performClick()
        wait_text("存单回款");rule.onNodeWithText("修改来源存单记录").assertDoesNotExist()
        click_list("存单回款");wait_text("流水详情")
        wait_text("修改来源存单记录");click_list("修改来源存单记录")
        rule.onNodeWithText("本金").performTextReplacement("30000")
        save();wait_text("+30 443.84 CNY");shot("deposit-source-corrected")
        rule.onNodeWithText("流水详情").assertExists()
        rule.onNodeWithContentDescription("返回").performClick();wait_text("余额变化")
        rule.onNodeWithText("+30 443.84 CNY").assertExists()
        runBlocking {
            assertEquals(3044384,db.ledger().cash_one(account_id,"CNY")!!.balance_minor)
            assertEquals("CLOSED",db.ledger().deposit(1)!!.status)
            assertEquals(3044384L,db.ledger().source_entry("TERM_CLOSE",1)!!.delta_minor)
        }
    }
    @Test fun investment_trade_price_and_history_are_independent() {
        runBlocking {
            val type = createType("合成基金")
            createInvestment(account_id,"合成投资","TEST",type,"CNY",R.parse_e8("10"),
                R.parse_e8("100"),R.parse_e8("100"))
        }
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performClick()
        wait_text("投资")
        rule.onNodeWithText("投资").performClick()
        wait_text("合成投资")
        rule.onNodeWithText("合成投资").performClick()
        wait_text("交易历史")
        click_list("更新当前价格")
        wait_text("该当前价格由此标的在所有账户中共用。")
        rule.onNodeWithText("当前每份价格（最多5位小数）").performTextReplacement("105")
        save()
        wait_text("交易历史")
        click_list("买入")
        wait_text("成交单价")
        rule.onNodeWithText("份额").performScrollTo().performTextReplacement("2")
        rule.onNodeWithText("成交单价").performScrollTo().performTextReplacement("90")
        rule.onNodeWithText("交易手续费").performScrollTo().performTextReplacement("1.50")
        rule.onNodeWithContentDescription("记账日期:", substring = true).performScrollTo().performClick()
        native_picker(true)
        rule.onNodeWithContentDescription("记账时间:", substring = true).performScrollTo().performClick()
        native_picker(false)
        rule.onNode(isToggleable()).performScrollTo().assertIsOff()
        save()
        wait_text("12 份")
        shot("investment-trade")
        click_record("trade-record-1")
        wait_text("交易详情")
        wait_text("181.50 CNY")
        wait_text("1.50 CNY")
        rule.activityRule.scenario.recreate()
        wait_text("交易详情")
        click_list("修改买卖记录")
        wait_text("成交单价")
        rule.onNodeWithText("份额").performScrollTo().performTextReplacement("3")
        rule.onNodeWithText("成交单价").performScrollTo().performTextReplacement("80")
        rule.onNodeWithText("交易手续费").performScrollTo().performTextReplacement("2.00")
        save()
        wait_text("242.00 CNY")
        window_shot("trade-detail-window", "修改买卖记录")
        rule.onNodeWithContentDescription("返回").performClick()
        wait_text("交易历史")
        click_record("trade-record-1")
        wait_text("交易详情")
        click_list("删除买卖记录")
        wait_text("确认删除")
        rule.onNodeWithText("确认删除").performScrollTo().performClick()
        wait_text("这笔交易已删除或不存在。")
        rule.onNodeWithContentDescription("返回").performClick()
        wait_text("交易历史")
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNodeWithContentDescription("返回").performClick()
        rule.onNode(hasText("投资") and hasClickAction()).performClick()
        wait_text("总投资市值")
        rule.onNodeWithContentDescription("所有投资品").performClick()
        wait_text("合成投资")
        rule.onNodeWithText("合成投资").performClick()
        click_list("编辑标的与当前价格")
        rule.onNodeWithText("当前每份价格（最多5位小数）").performScrollTo().performTextReplacement("120")
        save()
        wait_text("按账户分别计算成本")
        shot("investment-price")
        runBlocking {
            assertTrue(db.trades().first_trades(1, 50).isEmpty())
            assertEquals(R.parse_e8("10"), db.positions().investment(1)!!.holding_quantity_e8)
            assertEquals(12000000L, db.instruments().instrument(1)!!.current_price_e5)
            assertNull(db.cash().cash_one(account_id, "CNY"))
        }
    }

    @Test fun searchable_multi_currency_accounts_and_cash_source_correction() {
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performClick()
        for ((code, amount) in listOf("USD" to "100.00", "JPY" to "100", "KWD" to "1.234")) {
            rule.onNodeWithText("编辑").performClick()
            rule.onNodeWithText("＋ 添加账户").performScrollTo().performClick()
            val choices = rule.onAllNodesWithContentDescription("币种:", substring = true)
            choices[choices.fetchSemanticsNodes().lastIndex].performScrollTo().performClick()
            rule.onNodeWithText("搜索代码或币种名称").performTextInput(if (code == "KWD") "科威特" else code)
            rule.onNode(hasText(code) and !hasSetTextAction()).performClick()
            val amounts = rule.onAllNodesWithText("余额")
            amounts[amounts.fetchSemanticsNodes().lastIndex].performScrollTo().performTextInput(amount)
            save()
            wait_text(code)
        }
        shot("multi-currency")
        runBlocking {
            assertEquals(1234L, db.cash().cash_one(account_id, "KWD")!!.balance_minor)
            assertEquals(3, graph.cash.observe_cash(account_id).first().size)
        }
        rule.onNodeWithContentDescription("余额变化 · USD").performScrollTo().performClick()
        wait_text("手动余额调整")
        rule.onNodeWithText("修改余额变化").assertDoesNotExist()
        shot("cash-entry-list")
        click_list("手动余额调整")
        wait_text("流水详情")
        window_shot("cash-entry-detail-window", "修改余额变化")
        click_list("修改余额变化")
        rule.onNodeWithText("变化金额").performScrollTo().performTextReplacement("90.00")
        rule.onNodeWithText("备注").performScrollTo().performTextInput("余额调整验证")
        rule.onNodeWithText("备注").performImeAction()
        window_shot("cash-entry-form-window", "保存")
        save()
        wait_text("+90.00 USD")
        shot("cash-corrected")
        rule.onNodeWithContentDescription("返回").performClick()
        val tradeId = runBlocking {
            val type = createType("合成基金")
            val instrument = graph.commands.execute(SaveInstrument(UUID.randomUUID().toString(), null,
                null, "来源投资", "", type, "USD", 12000000)).id
            val position = graph.commands.execute(CreateInvestmentPosition(UUID.randomUUID().toString(),
                account_id, instrument)).id
            graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), position,
                Direction.BUY, R.parse_e8("1"), R.parse_e8("10"), graph.clock.millis(), true)).id
        }
        wait_text("投资买卖")
        click_list("投资买卖")
        wait_text("流水详情")
        click_list("修改来源买卖记录")
        wait_text("成交单价")
        rule.onNodeWithText("份额").performScrollTo().performTextReplacement("2")
        save()
        wait_text("-20.00 USD")
        shot("cash-source-corrected")
        rule.activityRule.scenario.recreate()
        wait_text("流水详情")
        wait_text("-20.00 USD")
        runBlocking {
            assertEquals(7000L, db.cash().cash_one(account_id, "USD")!!.balance_minor)
            assertEquals(R.parse_e8("2"), db.trades().trade(tradeId)!!.quantity_e8)
            assertEquals(-2000L, db.cash().source_entry("TRADE", tradeId)!!.delta_minor)
        }
        click_list("修改来源买卖记录")
        wait_text("成交单价")
        rule.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        save()
        wait_text("这条流水已删除或已取消现金联动。")
        rule.onNodeWithText("修改来源买卖记录").assertDoesNotExist()
        shot("cash-entry-unlinked")
    }

    @Test fun buy_cost_profit_and_global_zero_position_reentry() {
        runBlocking {
            val type = createType("合成基金")
            val instrument = graph.commands.execute(SaveInstrument(UUID.randomUUID().toString(), null,
                null, "QQQ", "QQQ", type, "CNY", 12000000)).id
            val position = graph.commands.execute(CreateInvestmentPosition(UUID.randomUUID().toString(),
                account_id, instrument)).id
            graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), position,
                Direction.BUY, R.parse_e8("10"), R.parse_e8("100"), graph.clock.millis() - 1000, false))
        }
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performClick()
        rule.onNodeWithText("投资").performClick()
        wait_text("QQQ")
        rule.onAllNodesWithText("QQQ")[0].performClick()
        wait_text("交易历史")
        click_list("卖出")
        wait_text("成交单价")
        rule.onNodeWithText("份额").performScrollTo().performTextReplacement("10")
        rule.onNodeWithText("成交单价").performScrollTo().performTextReplacement("130")
        save()
        wait_text("0 份")
        rule.onNodeWithContentDescription("返回").performClick()
        wait_text("暂无持仓")
        rule.onNodeWithText("已清仓").assertDoesNotExist()
        click_list("所有投资品")
        wait_text("QQQ")
        rule.onAllNodesWithText("QQQ")[0].performClick()
        wait_text("累计已实现盈亏 300.00 CNY")
        shot("global-zero-position")
        click_list("买入")
        wait_text("成交单价")
        rule.onNodeWithText("份额").performScrollTo().performTextReplacement("5")
        rule.onNodeWithText("成交单价").performScrollTo().performTextReplacement("160")
        save()
        wait_text("5 份")
        runBlocking {
            val profit = graph.investments.observe_profit(1).first()!!
            assertEquals(0, java.math.BigDecimal("160").compareTo(profit.average_cost!!))
            assertEquals(0, java.math.BigDecimal("300").compareTo(profit.realized!!))
        }
    }
    @Test fun root_capsule_state_and_popped_entry_stores_are_retained_and_released() {
        wait_text("合成账户 A")
        rule.onNodeWithTag("root-capsule").assertExists()
        val baseline = dev.valnook.app.navigation.EntryLifetime.active.get()
        val cleared = dev.valnook.app.navigation.EntryLifetime.cleared.get()
        repeat(6) {
            rule.onNodeWithTag("account-total-$account_id").performClick()
            rule.onNodeWithTag("root-capsule").assertDoesNotExist()
            rule.onNodeWithText("编辑").performClick()
            wait_text("编辑账户")
            rule.onNodeWithContentDescription("返回").performClick()
            rule.onNodeWithContentDescription("返回").performClick()
            wait_text("合成账户 B")
            rule.waitUntil(5000) { dev.valnook.app.navigation.EntryLifetime.active.get() == baseline }
        }
        assertTrue(dev.valnook.app.navigation.EntryLifetime.cleared.get() >= cleared + 12)
        rule.onNode(hasText("设置") and hasClickAction()).performClick()
        wait_text("主币种")
        wait_text("未设置")
        rule.onNodeWithTag("root-capsule").assertExists()
        rule.onNode(hasText("账户") and hasClickAction()).performClick()
        wait_text("合成账户 A")
        window_shot("root-capsule-window")
    }

    @Test fun synthetic_gallery_light_dark_narrow_wide_and_large_text() {
        val a=listOf(SavingsAccount(1,"合成账户 · 很长的账户名称与备注","当前持有的多币种资产"))
        val balances=listOf(
            CashBalance(1,Currency.of("CNY"),Long.MAX_VALUE,1,id=1,name="合成人民币"),
            CashBalance(1,Currency.of("JPY"),0,1,id=2,name="合成日元")
        )
        val deposits=listOf(TermDeposit(1,1,Currency.of("CNY"),1000000,R.parse_e8("3"),
            LocalDate.parse("2026-01-01").toEpochDay(),LocalDate.parse("2026-04-01").toEpochDay(),7397,false,true,null))
        val asset=Investment(1,1,1,"合成基金","一项名称非常长的合成投资资产","TEST-VERY-LONG-SYMBOL-1234567890123456789012345678901234567890",Currency.of("USD"),R.parse_e8("38.991"),R.parse_e8("26.1161"),0,remainingCost="1000",realizedProfit="0")
        for(dark in listOf(false,true))for((width,height) in listOf(320 to 720,360 to 800,420 to 900,600 to 960,840 to 360))for(scale in listOf(1f,2f)) {
            val density=if(width<=420)2f else 1f
            val name="gallery-${if(dark)"dark" else "light"}-$width-$scale"
            show_scene(name) {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme(dark) {
                        Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.dp).height(height.dp).testTag("gallery")) {
                            androidx.compose.foundation.lazy.LazyColumn(contentPadding=PaddingValues(Space.md)) {
                                item{androidx.compose.material3.Text(a.first().name,style=androidx.compose.material3.MaterialTheme.typography.titleLarge)}
                                item{androidx.compose.material3.Text(a.first().note)}
                                item{AmountText(R.format_display(balances.first().balance_minor,2),"CNY")}
                                item{HoldingRow(asset,{})}
                                item{EmptyState("合成空状态")}
                            }
                        }
                        }
                    }
                }
            }
            val codeLayouts = mutableListOf<TextLayoutResult>()
            val symbolNode = rule.onNodeWithTag("holding-symbol-1", useUnmergedTree = true)
            symbolNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(codeLayouts)) }
            assertEquals(1, codeLayouts.single().lineCount)
            if (width == 420 && scale == 1f) assertTrue(codeLayouts.single().isLineEllipsized(0))
            val codeBounds = symbolNode.fetchSemanticsNode().boundsInRoot
            val currencyNode = rule.onNodeWithTag("holding-currency-1", useUnmergedTree = true).assertTextEquals("USD")
            val currencyBounds = currencyNode.fetchSemanticsNode().boundsInRoot
            assertEquals(codeBounds.top, currencyBounds.top, 1f)
            assertTrue(codeBounds.right <= currencyBounds.left)
            shot(name,"gallery",(width*density).toInt())
            show_scene("accounts-$name") {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme(dark) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.dp).height(height.dp).testTag("account-cards")) {
                                val snapshot = AssetSnapshot(a, balances, emptyList(), emptyList(), emptyList(), AppSettings(Currency.of("CNY")))
                                AccountsContent(dev.valnook.domain.calculation.AssetValuation.calculate(snapshot), {})
                            }
                        }
                    }
                }
            }
            shot("account-cards-${if(dark)"dark" else "light"}-$width-$scale", "account-cards", (width*density).toInt())
            show_scene("cash-$name") {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme(dark) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.dp).height(height.dp).testTag("cash-cards")) {
                                CashContent(balances,on_open={})
                            }
                        }
                    }
                }
            }
            shot("cash-cards-${if(dark)"dark" else "light"}-$width-$scale","cash-cards",(width*density).toInt())
            show_scene("detail-$name") {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme(dark) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.dp).height(height.dp).testTag("cash-detail")) {
                                CashEntryDetailContent(CashEntry(1,1,Currency.of("USD"),-123456789,graph.clock.millis(),
                                    CashSource.CASH_SET,null,null,"一条较长的备注，用于验证窄屏和大字体下的信息完整显示。",1),a.first().name,{})
                            }
                        }
                    }
                }
            }
            shot("cash-detail-${if(dark)"dark" else "light"}-$width-$scale","cash-detail",(width*density).toInt())
            val editCash = rule.activity.getString(dev.valnook.core.designsystem.R.string.edit_cash_change)
            rule.onNode(hasScrollAction()).performScrollToNode(hasText(editCash))
            rule.onNodeWithText(editCash).assertIsDisplayed()
            if(width in listOf(320,840)&&scale==2f) {
                show_scene("trade-detail-$name") {
                    CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                        ValnookTheme(dark) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.width(width.dp).height(height.dp).testTag("trade-detail")) {
                                    TradeDetailContent(asset,Trade(1,1,Direction.BUY,R.parse_e8("10000"),R.parse_e8("120"),
                                        120000000,Currency.of("USD"),true,graph.clock.millis()),a.first().name,{},{})
                                }
                            }
                        }
                    }
                }
                shot("trade-detail-${if(dark)"dark" else "light"}-$width-$scale","trade-detail",(width*density).toInt())
                for(label in listOf(
                    rule.activity.getString(dev.valnook.core.designsystem.R.string.edit_trade),
                    rule.activity.getString(dev.valnook.core.designsystem.R.string.delete_trade)
                )) {
                    rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                    rule.onNodeWithText(label).assertIsDisplayed()
                }
                show_scene("deposit-detail-$name") {
                    CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                        ValnookTheme(dark) {
                            Box(Modifier.fillMaxSize()) {
                                Box(Modifier.width(width.dp).height(height.dp).testTag("deposit-detail")) {
                                    DepositDetailContent(TermDeposit(1,1,Currency.of("CNY"),123456789,R.parse_e8("3"),
                                        LocalDate.parse("2026-01-01").toEpochDay(),LocalDate.parse("2026-04-01").toEpochDay(),913242,false,true,null),
                                        LocalDate.parse("2026-09-30").toEpochDay(),a.first().name,{},{})
                                }
                            }
                        }
                    }
                }
                shot("deposit-detail-${if(dark)"dark" else "light"}-$width-$scale","deposit-detail",(width*density).toInt())
                for(label in listOf(
                    rule.activity.getString(dev.valnook.core.designsystem.R.string.edit_deposit),
                    rule.activity.getString(dev.valnook.core.designsystem.R.string.close_deposit)
                )) {
                    rule.onNode(hasScrollAction()).performScrollToNode(hasText(label))
                    rule.onNodeWithText(label).assertIsDisplayed()
                }
            }
        }
        show_scene("accounts-empty"){ValnookTheme{AccountsContent(dev.valnook.domain.calculation.AssetValuation.calculate(AssetSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), AppSettings())), {})}};shot("accounts-empty")
        show_scene("deposits-gallery"){ValnookTheme{DepositsContent(deposits,LocalDate.parse("2026-04-01").toEpochDay(),{},{})}};shot("deposits-gallery")
    }

    @Test fun compact_holding_opens_two_column_history_and_updates_shared_price() {
        val investmentsLabel = localized(dev.valnook.core.designsystem.R.string.investments)
        val historyLabel = localized(dev.valnook.feature.investments.R.string.investment_trade_history)
        val feeLabel = localized(dev.valnook.core.designsystem.R.string.trade_fee) + " 1.50 CNY"
        val sellFeeLabel = localized(dev.valnook.core.designsystem.R.string.trade_fee) + " 2.00 CNY"
        val buyTotalLabel = localized(dev.valnook.core.designsystem.R.string.buy) + " · 1 301.50 CNY"
        val sellTotalLabel = localized(dev.valnook.core.designsystem.R.string.sell) + " · 698.00 CNY"
        val buyUnitPriceLabel = localized(dev.valnook.core.designsystem.R.string.execution_price) + " 1300 CNY"
        val sellUnitPriceLabel = localized(dev.valnook.core.designsystem.R.string.execution_price) + " 1400 CNY"
        val updatePriceLabel = localized(dev.valnook.feature.investments.R.string.instrument_price_edit_title)
        val sharedPriceHint = localized(dev.valnook.feature.investments.R.string.instrument_price_shared_hint)
        val currentPriceLabel = localized(dev.valnook.feature.investments.R.string.instrument_current_price)
        val saveLabel = localized(dev.valnook.core.designsystem.R.string.save)
        var positionId = 0L
        var tradeId = 0L
        var sellTradeId = 0L
        runBlocking {
            val type = createType("A股股票")
            positionId = createInvestment(account_id,"贵州茅台","600519.SH",type,"CNY",
                R.parse_e8("10"),R.parse_e8("1468.2"),R.parse_e8("1200"))
            tradeId = graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), positionId,
                Direction.BUY, R.parse_e8("1"), R.parse_e8("1300"), graph.clock.millis(), false,
                fee_minor = 150)).id
            sellTradeId = graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), positionId,
                Direction.SELL, R.parse_e8("0.5"), R.parse_e8("1400"), graph.clock.millis(), false,
                fee_minor = 200)).id
        }
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performClick()
        wait_text(investmentsLabel)
        rule.onNodeWithText(investmentsLabel).performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("holding-$positionId").fetchSemanticsNodes().isNotEmpty() }
        wait_text("1209.22727")
        rule.onNodeWithTag("holding-$positionId").performClick()
        wait_text(historyLabel)
        wait_text(feeLabel)
        wait_text(sellFeeLabel)
        wait_text(buyTotalLabel)
        wait_text(sellTotalLabel)
        wait_text(buyUnitPriceLabel)
        wait_text(sellUnitPriceLabel)
        rule.onNodeWithTag("trade-record-$tradeId").assertExists()
        rule.onNodeWithTag("trade-record-$sellTradeId").assertExists()
        rule.onNodeWithText(updatePriceLabel).performScrollTo().assertIsDisplayed().performClick()
        wait_text(sharedPriceHint)
        rule.onNodeWithText(currentPriceLabel).performTextReplacement("1500")
        save(saveLabel)
        wait_text(historyLabel)
        runBlocking {
            val instrumentId = db.positions().investment(positionId)!!.instrument_id
            assertEquals(150000000L, db.instruments().instrument(instrumentId)!!.current_price_e5)
        }
    }
    @Test fun all_account_totals_settings_and_root_scroll_survive_switches() {
        val rateLabel = localized(dev.valnook.feature.settings.R.string.settings_rate)
        val baseCurrencyLabel = localized(dev.valnook.feature.settings.R.string.settings_base_currency)
        runBlocking {
            graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), account_id, 1,
                "合成账户 A", "仅测试数据", listOf(CashBalanceChange("CNY", 100000, null), CashBalanceChange("USD", 10000, null))))
            graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), 2, 1,
                "合成账户 B", "账户隔离", listOf(CashBalanceChange("CNY", 50000, null))))
            val type = createType("ETF")
            val instrument = graph.commands.execute(SaveInstrument(UUID.randomUUID().toString(), null, null,
                "QQQ", "QQQ", type, "USD", 10000000)).id
            val position = graph.commands.execute(CreateInvestmentPosition(UUID.randomUUID().toString(),
                account_id, instrument)).id
            graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), position,
                Direction.BUY, R.parse_e8("10"), R.parse_e8("100"), graph.clock.millis(), false))
            graph.commands.execute(OpenTermDeposit(UUID.randomUUID().toString(), account_id, "USD", 100000,
                0, LocalDate.parse("2026-01-01").toEpochDay(), LocalDate.parse("2027-01-01").toEpochDay(), false))
            repeat(40) { createAccount("合成长列表 ${it + 1}","仅用于滚动验证") }
        }
        wait_text("合成账户 A")
        rule.onNode(hasText("设置") and hasClickAction()).performClick()
        rule.onNode(hasText("主币种") and hasClickAction()).performClick()
        wait_text("主币种")
        wait_text("未设置")
        rule.onNodeWithContentDescription("币种:", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("搜索代码或币种名称").performTextInput("CNY")
        rule.onNode(hasText("CNY") and hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        rule.onNodeWithText("＋ 添加汇率").performScrollTo().performClick()
        rule.onAllNodesWithContentDescription("币种:", substring = true)[1].performScrollTo().performClick()
        rule.onNodeWithText("搜索代码或币种名称").performTextInput("USD")
        rule.onNode(hasText("USD") and hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        rule.onNode(hasText(rateLabel) and hasSetTextAction()).performScrollTo().performTextReplacement("7.2")
        rule.onNode(hasText(rateLabel) and hasSetTextAction()).performImeAction()
        rule.onNodeWithText("保存设置").performScrollTo().performClick()
        wait_text("设置已保存")
        rule.onNodeWithContentDescription(localized(dev.valnook.app.R.string.nav_back)).performClick()
        rule.onNode(hasText("账户") and hasClickAction()).performClick()
        wait_text("16620.00 CNY")
        wait_text("2220.00 CNY")
        rule.onNodeWithTag("accounts-list").performScrollToNode(hasText("合成账户 A"))
        wait_text("可用现金 1720.00 CNY")
        shot("all-account-total-16620")
        rule.onNodeWithTag("accounts-list").performScrollToNode(hasText("合成长列表 40"))
        rule.onNodeWithText("合成长列表 40").assertIsDisplayed()
        val capsuleTop = rule.onNodeWithTag("root-capsule").fetchSemanticsNode().boundsInWindow.top
        assertTrue(rule.onNodeWithText("合成长列表 40").fetchSemanticsNode().boundsInWindow.bottom <= capsuleTop)
        window_shot("long-list-above-capsule")
        rule.onNode(hasText("投资") and hasClickAction()).performClick()
        wait_text("总投资市值"); wait_text("7200.00 CNY")
        rule.onNodeWithTag("investment-market-total").assertTextEquals("7200.00 CNY")
        rule.onNodeWithTag("investment-account-toggle-$account_id").performScrollTo().performClick()
        wait_text("QQQ")
        rule.onNode(hasText("设置") and hasClickAction()).performClick()
        wait_text(baseCurrencyLabel)
        rule.onNode(hasText("投资") and hasClickAction()).performClick()
        rule.onNodeWithTag("investment-account-toggle-$account_id").assertTextContains("▴", substring = true)
        rule.onNode(hasText("账户") and hasClickAction()).performClick()
        rule.onNodeWithText("合成长列表 40").assertIsDisplayed()
        val readiness = mutableListOf<Double>()
        repeat(30) {
            val start = System.nanoTime()
            rule.onNode(hasText("投资") and hasClickAction()).performClick()
            wait_text("总投资市值"); wait_text("7200.00 CNY")
            rule.onNodeWithTag("investment-market-total").assertTextEquals("7200.00 CNY")
            rule.waitForIdle()
            readiness.add((System.nanoTime() - start) / 1_000_000.0)
            rule.onNode(hasText("账户") and hasClickAction()).performClick()
            rule.onNodeWithText("合成长列表 40").assertIsDisplayed()
        }
        readiness.sort()
        val output = org.json.JSONObject().put("path", "root switch to investment ready semantics; retained snapshot")
            .put("harness", "Compose test clock; not physical frame benchmark").put("accounts", 42)
            .put("repetitions", 30).put("p50_ms", readiness[14]).put("p95_ms", readiness[28])
        PlatformTestStorageRegistry.getInstance().openOutputFile("ui-readiness.json").use { it.write(output.toString(2).toByteArray()) }
    }
    @Test fun cash_balance_editor_is_available_only_in_editor_and_ledger() {
        val editBalance = localized(dev.valnook.feature.cash.R.string.cash_edit_balance)
        val balanceLabel = localized(dev.valnook.feature.cash.R.string.cash_current_balance)
        val saveLabel = localized(dev.valnook.feature.cash.R.string.cash_save)
        val backLabel = localized(dev.valnook.app.R.string.nav_back)
        var cashId = 0L
        runBlocking { graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(), account_id,
            "CNY", 10000, null)); cashId = graph.cash.observe_cash(account_id).first().single().id }
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-toggle-$account_id").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(24f, 24f))
        }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("account-cash-$cashId").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(editBalance).assertDoesNotExist()
        rule.onNodeWithText("人民币").assertDoesNotExist()
        shot("cash-overview-read-only")
        rule.onNodeWithTag("account-cash-$cashId").performScrollTo().performClick()
        wait_text(editBalance)
        click_list(editBalance)
        wait_text(balanceLabel)
        rule.onNodeWithText(balanceLabel).performScrollTo().performTextReplacement("135.00")
        save(saveLabel)
        wait_text("135.00")
        runBlocking { assertEquals(13500L, db.cash().cash_one(account_id, "CNY")!!.balance_minor) }
        window_shot("cash-ledger-balance-editor", editBalance)
        rule.onNodeWithContentDescription(backLabel).performClick()
        wait_text("CNY")
        rule.onNodeWithText(editBalance).assertDoesNotExist()
    }

    @Test fun account_accordion_and_detail_sections_show_negative_cash() {
        var cashId = 0L
        runBlocking {
            graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(), account_id, "CNY", -12345, null,
                name = "人民币现金", note = "可透支"))
            cashId = graph.cash.observe_cash(account_id).first().single().id
            val settings = graph.overview.snapshot().settings
            graph.settingsWriter.applyChange(SaveFinancialSettings(settings.revision, Currency.of("CNY"), emptyList()))
        }
        rule.onNodeWithTag("account-toggle-$account_id").assertIsDisplayed().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(24f, 24f))
        }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("account-cash-$cashId").fetchSemanticsNodes().isNotEmpty() }
        rule.mainClock.advanceTimeBy(200); rule.waitForIdle()
        rule.onNodeWithTag("account-cash-$cashId").performScrollTo().assertIsDisplayed().assertTextContains("-123.45 CNY")
        rule.onNodeWithTag("account-total-$account_id").performScrollTo().performClick()
        rule.onNodeWithTag("account-section-ACCOUNTS").assertIsDisplayed()
        rule.onNodeWithTag("account-section-ACCOUNTS").assertIsSelected()
        val tabsTop = rule.onNodeWithTag("account-detail-tabs").fetchSemanticsNode().boundsInRoot.top
        rule.onNodeWithTag("account-section-DEPOSITS").assertIsDisplayed()
        rule.onNodeWithTag("account-section-INVESTMENTS").assertIsDisplayed().performClick()
        rule.onNodeWithTag("account-section-INVESTMENTS").assertIsDisplayed()
        rule.onNodeWithTag("account-section-INVESTMENTS").assertIsSelected()
        assertEquals(tabsTop, rule.onNodeWithTag("account-detail-tabs").fetchSemanticsNode().boundsInRoot.top, 0.1f)
        rule.onNodeWithTag("account-section-DEPOSITS").performClick().assertIsSelected()
        rule.onNodeWithTag("account-section-ACCOUNTS").performClick().assertIsSelected()
        wait_text("人民币现金")
        shot("account-detail-capsule-tabs")
    }

    private fun drag_order_row(key: String, target: String) {
        val from = rule.onNodeWithTag("sort-row-$key").fetchSemanticsNode().boundsInRoot
        val to = rule.onNodeWithTag("sort-row-$target").fetchSemanticsNode().boundsInRoot
        val distance = to.center.y - from.center.y
        rule.onNodeWithTag("sort-handle-$key").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, distance * 1.25f), durationMillis = 700)
        }
        rule.mainClock.advanceTimeBy(200)
        rule.waitForIdle()
    }

    @Test fun main_account_sort_cancel_save_and_investment_order_are_consistent() {
        val second = runBlocking { graph.overview.snapshot().accounts.single { it.id != account_id }.id }
        runBlocking {
            val type = createType("股票")
            createInvestment(account_id, "平安银行", "000001.SZ", type, "CNY", 100000000, 1200000000, 1000000000)
            createInvestment(second, "贵州茅台", "600519.SH", type, "CNY", 100000000, 140000000000, 130000000000)
            val settings = graph.overview.snapshot().settings
            graph.settingsWriter.applyChange(SaveFinancialSettings(settings.revision, Currency.of("CNY"), emptyList()))
        }
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-order-open").assertIsEnabled().performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("sort-row-$account_id").fetchSemanticsNodes().isNotEmpty() }
        drag_order_row(account_id.toString(), second.toString())
        assertTrue(rule.onNodeWithTag("sort-row-$second").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithTag("sort-row-$account_id").fetchSemanticsNode().boundsInRoot.top)
        shot("main-account-sort-draft")
        rule.onNodeWithContentDescription(localized(dev.valnook.app.R.string.nav_back)).performClick()
        runBlocking { assertEquals(listOf(account_id, second), graph.overview.snapshot().accounts.map { it.id }) }
        rule.onNodeWithTag("account-order-open").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("sort-row-$account_id").fetchSemanticsNodes().isNotEmpty() }
        drag_order_row(account_id.toString(), second.toString())
        rule.onNodeWithTag("account-order-save").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("account-order-save").fetchSemanticsNodes().isEmpty() }
        assertTrue(rule.onNodeWithTag("account-total-$second").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithTag("account-total-$account_id").fetchSemanticsNode().boundsInRoot.top)
        rule.onNode(hasText(localized(dev.valnook.app.R.string.nav_investments)) and hasClickAction()).performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("investment-account-toggle-$second").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(rule.onNodeWithTag("investment-account-toggle-$second").fetchSemanticsNode().boundsInRoot.top <
            rule.onNodeWithTag("investment-account-toggle-$account_id").fetchSemanticsNode().boundsInRoot.top)
        shot("investment-shared-account-order")
        rule.activityRule.scenario.recreate()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("investment-account-toggle-$second").fetchSemanticsNodes().isNotEmpty() }
        runBlocking { assertEquals(listOf(second, account_id), graph.overview.snapshot().accounts.map { it.id }) }
    }

    @Test fun subaccount_sort_only_persists_when_account_edit_is_saved() {
        val ids = runBlocking {
            val owner = graph.overview.snapshot().accounts.single { it.id == account_id }
            graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), account_id, owner.revision,
                owner.name, owner.note, listOf(
                    BalanceAccountChange("CNY", 12345, null, name = "人民币现金"),
                    BalanceAccountChange("USD", 456, null, name = "美元现金"),
                    BalanceAccountChange("CNY", -123, null, name = "Visa 信用", type = BalanceAccountType.CREDIT,
                        credit = CreditAccountInput(50000, 12, CreditDueRule.AfterStatementDays(20), null)))))
            graph.overview.snapshot().cash.filter { it.account_id == account_id }.map { it.id }
        }
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performScrollTo().performClick()
        rule.onNodeWithText(localized(dev.valnook.app.R.string.nav_edit)).performClick()
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("subaccount-order-open") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("subaccount-order-open").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("sort-row-${ids[0]}").fetchSemanticsNodes().isNotEmpty() }
        drag_order_row(ids[0].toString(), ids[1].toString())
        shot("subaccount-sort-sheet", "subaccount-sort-sheet")
        rule.onNodeWithTag("subaccount-order-confirm").performClick()
        runBlocking { assertEquals(ids, graph.overview.snapshot().cash.filter { it.account_id == account_id }.map { it.id }) }
        rule.onNodeWithContentDescription(localized(dev.valnook.app.R.string.nav_back)).performClick()
        rule.onNodeWithText(localized(dev.valnook.app.R.string.nav_edit)).performClick()
        rule.waitUntil(5000) { rule.onAllNodes(hasTestTag("subaccount-order-open") and SemanticsMatcher.keyNotDefined(SemanticsProperties.Disabled)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("subaccount-order-open").performClick()
        rule.waitUntil(5000) { rule.onAllNodesWithTag("sort-row-${ids[0]}").fetchSemanticsNodes().isNotEmpty() }
        drag_order_row(ids[0].toString(), ids[1].toString())
        rule.onNodeWithTag("subaccount-order-confirm").performClick()
        save(localized(dev.valnook.feature.accounts.R.string.account_save))
        wait_text("美元现金")
        runBlocking {
            val rows = graph.overview.snapshot().cash.filter { it.account_id == account_id }
            assertEquals(listOf(ids[1], ids[0], ids[2]), rows.map { it.id })
            assertEquals(listOf(456L, 12345L, -123L), rows.map { it.balance_minor })
        }
        shot("subaccount-order-saved")
    }

    @Test fun demo_account_order_is_isolated_and_resets_from_prebuilt_asset() {
        runBlocking {
            val realOrder = graph.overview.snapshot().accounts.map { it.id }
            sessions.enterDemo()
            val demoOrder = graph.overview.snapshot().accounts.map { it.id }
            assertEquals(12, demoOrder.size)
            val writer = requireNotNull(graph.accountOrderWriter)
            writer.saveOrder(demoOrder, demoOrder.reversed())
            assertEquals(demoOrder.reversed(), graph.overview.snapshot().accounts.map { it.id })
            sessions.exitDemo()
            assertEquals(realOrder, graph.overview.snapshot().accounts.map { it.id })
            try { writer.saveOrder(demoOrder.reversed(), demoOrder); fail("Expired demo writer") }
            catch (error: DomainException) { assertEquals(ErrorCode.SESSION_EXPIRED, error.code) }
            sessions.enterDemo()
            assertEquals(demoOrder, graph.overview.snapshot().accounts.map { it.id })
            sessions.exitDemo()
        }
        wait_text("合成账户 A")
    }

    @Test fun account_detail_tabs_fit_narrow_dark_layout_with_large_text() {
        val vm = dev.valnook.feature.accounts.AccountsViewModel(graph.overview)
        rule.runOnUiThread {
            rule.activity.setContent {
                ValnookTheme(dark_theme = true) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                        Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(320.dp).fillMaxHeight().testTag("narrow-account-detail")) {
                            dev.valnook.feature.accounts.AccountDetailScreen(vm, account_id,
                                dev.valnook.feature.accounts.AccountDetailSection.ACCOUNTS,
                                cashContent = { androidx.compose.material3.Text("Cash content") },
                                depositContent = { androidx.compose.material3.Text("Deposit content") },
                                investmentContent = { androidx.compose.material3.Text("Investment content") })
                        }
                        }
                    }
                }
            }
        }
        rule.waitUntil(5000) { rule.onAllNodesWithTag("account-detail-tabs").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(320f * rule.activity.resources.displayMetrics.density,
            rule.onNodeWithTag("narrow-account-detail").fetchSemanticsNode().boundsInRoot.width, 0.1f)
        listOf("ACCOUNTS", "DEPOSITS", "INVESTMENTS").forEach {
            rule.onNodeWithTag("account-section-$it").assertIsDisplayed().performClick().assertIsSelected()
        }
        shot("account-detail-narrow-dark-large-text", "narrow-account-detail")
        val englishConfig = android.content.res.Configuration(rule.activity.resources.configuration).apply {
            setLocale(java.util.Locale.ENGLISH)
        }
        val englishContext = rule.activity.createConfigurationContext(englishConfig)
        rule.runOnUiThread {
            rule.activity.setContent {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides englishContext,
                    androidx.compose.ui.platform.LocalConfiguration provides englishConfig,
                    LocalDensity provides Density(rule.activity.resources.displayMetrics.density, 1.3f)) {
                    ValnookTheme(dark_theme = false) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(320.dp).fillMaxHeight().testTag("english-account-detail")) {
                                dev.valnook.feature.accounts.AccountDetailScreen(vm, account_id,
                                    dev.valnook.feature.accounts.AccountDetailSection.ACCOUNTS,
                                    cashContent = { androidx.compose.material3.Text("Cash content") },
                                    depositContent = { androidx.compose.material3.Text("Deposit content") },
                                    investmentContent = { androidx.compose.material3.Text("Investment content") })
                            }
                        }
                    }
                }
            }
        }
        listOf("Accounts", "Deposits", "Investments").forEach { label ->
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label).assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertFalse("Tab label should fit at 320dp: $label", layouts.single().isLineEllipsized(0))
        }
        rule.onNodeWithTag("account-section-DEPOSITS").performClick().assertIsSelected()
        shot("account-detail-narrow-english-light-large-text", "english-account-detail")
    }

    @Test fun long_reorder_list_auto_scrolls_while_dragging_without_losing_items() {
        val initial = (1..30).map { it.toString() }
        val ordered = androidx.compose.runtime.mutableStateOf(initial)
        var commits = 0
        rule.runOnUiThread {
            rule.activity.setContent {
                ValnookTheme {
                    Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(320.dp).height(420.dp).padding(top = 40.dp)) {
                        ReorderList(ordered.value.map { ReorderItem(it, "Account $it") }, { commits++; ordered.value = it },
                            "Reorder", "Move up", "Move down", Modifier.fillMaxSize().testTag("long-sort-list"))
                    }
                    }
                }
            }
        }
        rule.waitForIdle()
        val from = rule.onNodeWithTag("sort-handle-1").fetchSemanticsNode().boundsInRoot.center
        val bottom = rule.onNodeWithTag("long-sort-list").fetchSemanticsNode().boundsInRoot.bottom - 30f
        rule.mainClock.autoAdvance = false
        try {
            rule.onNodeWithTag("sort-handle-1").performTouchInput {
                down(center)
                moveTo(center + androidx.compose.ui.geometry.Offset(0f, bottom - from.y))
            }
            repeat(180) {
                rule.mainClock.advanceTimeByFrame()
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
        } finally {
            rule.onNodeWithTag("long-sort-list").performTouchInput { up() }
            rule.mainClock.autoAdvance = true
        }
        rule.waitForIdle()
        assertEquals(initial.toSet(), ordered.value.toSet())
        assertEquals(initial.size, ordered.value.size)
        shot("account-sort-long-list", "long-sort-list")
        assertTrue("Dragged row should move beyond the initially visible rows (commits=$commits): ${ordered.value}", ordered.value.indexOf("1") > 7)
    }

    @Test fun credit_account_form_and_account_detail_show_limit_billing_and_locked_type() {
        wait_text("合成账户 A")
        rule.onNodeWithTag("account-total-$account_id").performScrollTo().performClick()
        rule.onNodeWithText("编辑").performClick()
        wait_text("编辑账户")
        rule.onNodeWithText("＋ 添加账户").performScrollTo().performClick()
        rule.onNodeWithTag("input-账户类型").performScrollTo().performClick()
        rule.onNodeWithText("信用").performClick()
        rule.onNodeWithText("账户名称").performScrollTo().performTextReplacement("Visa 测试卡")
        rule.onNodeWithText("余额").performScrollTo().performTextReplacement("-52.00")
        rule.onNodeWithText("信用额度").performScrollTo().performTextInput("50.00")
        save()
        wait_text("Visa 测试卡")
        wait_text("信用账户")
        wait_text("欠款")
        wait_text("已超出信用额度 2.00 CNY")
        rule.onNodeWithText("编辑").performClick()
        wait_text("Visa 测试卡")
        rule.onNodeWithContentDescription("账户类型: 信用").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("删除账户").performScrollTo().assertIsDisplayed()
        window_shot("credit-account-form", "保存")
        rule.onNodeWithText("删除账户").performClick()
        rule.onNodeWithText("删除这个账户？").assertIsDisplayed()
        rule.onNodeWithTag("account-delete-confirm").performClick()
        wait_text("该账户存在余额、历史记录或业务关联，无法删除。")
        rule.onNodeWithContentDescription("返回").performClick()
        runBlocking {
            val credit = graph.overview.snapshot().cash.single { it.account_id == account_id && it.name == "Visa 测试卡" }
            assertEquals(BalanceAccountType.CREDIT, credit.type)
            assertEquals(-5_200L, credit.balance_minor)
            assertEquals(5_000L, credit.creditProfile?.creditLimitMinor)
        }
    }

    @Test fun library_menu_gates_instrument_creation_on_asset_types() {
        runBlocking { graph.settingsWriter.applyChange(SaveFinancialSettings(0,Currency.of("CNY"),
            listOf(FxRate(Currency.of("USD"),Currency.of("CNY"),java.math.BigDecimal("7"))))) }
        wait_text("合成账户 A")
        rule.onNode(hasText("投资") and hasClickAction()).performClick()
        wait_text("总投资市值")
        rule.onNodeWithText("新增标的").assertDoesNotExist()
        rule.onNodeWithText("资产类型管理").assertDoesNotExist()
        rule.onNodeWithContentDescription("所有投资品").performClick()
        wait_text("请先创建资产类型，再新增标的")
        rule.onNodeWithTag("root-capsule").assertDoesNotExist()
        rule.onNodeWithText("新增标的").assertIsNotEnabled()
        window_shot("library-type-required")
        click_list("创建资产类型")
        click_list("新增类型")
        rule.onNodeWithText("名称").performScrollTo().performTextInput("ETF")
        save()
        wait_text("ETF · 编辑")
        rule.onNodeWithContentDescription("返回").performClick()
        wait_text("新增标的")
        rule.onNodeWithText("新增标的").assertIsEnabled().performClick()
        wait_text("标的资料与当前价")
        rule.onNodeWithText("名称").performScrollTo().performTextInput("新建测试标的")
        rule.onNodeWithText("代码").performScrollTo().performTextInput("NEW")
        rule.onNodeWithContentDescription("资产类型:", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("ETF").performClick()
        rule.onNodeWithContentDescription("币种:", substring = true).performScrollTo().performClick()
        rule.onNodeWithText("搜索代码或币种名称").performTextInput("EUR")
        rule.onNodeWithText("欧元").assertIsDisplayed()
        rule.onNodeWithTag("currency-rate-EUR", useUnmergedTree = true).assertTextEquals("1")
        rule.onNodeWithText("搜索代码或币种名称").performImeAction()
        window_shot("currency-default-rate")
        rule.onNodeWithText("搜索代码或币种名称").performTextReplacement("USD")
        rule.onNodeWithText("美元").assertIsDisplayed()
        rule.onNodeWithTag("currency-rate-USD", useUnmergedTree = true).assertTextEquals("7")
        rule.onNodeWithText("搜索代码或币种名称").performImeAction()
        window_shot("currency-configured-rate")
        rule.onNode(hasText("USD") and hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        rule.onNodeWithContentDescription("币种: USD").assertExists()
        rule.onNodeWithText("当前每份价格（最多5位小数）").performScrollTo().performTextInput("10")
        save()
        wait_text("新建测试标的")
        runBlocking { assertEquals(1, graph.instruments.observeInstruments().first().size) }
        val nameBounds = rule.onNodeWithTag("instrument-name-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val typeBounds = rule.onNodeWithTag("instrument-type-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Type should immediately follow the name", typeBounds.left - nameBounds.right in 0f..24f)
        val codeBounds = rule.onNodeWithTag("instrument-code-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val currencyBounds = rule.onNodeWithTag("instrument-currency-1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(codeBounds.top, currencyBounds.top, 1f)
        assertTrue(codeBounds.top >= nameBounds.bottom)
        window_shot("library-management-complete")
        rule.onNodeWithText("新建测试标的").performClick()
        wait_text("按账户分别计算成本")
        rule.onNodeWithText("买入").assertDoesNotExist()
        rule.onNodeWithText("卖出").assertDoesNotExist()
        rule.onNodeWithText("录入期初持仓").assertDoesNotExist()
        runBlocking { assertTrue(graph.overview.observeSnapshot().first().positions.isEmpty()) }
        window_shot("compact-global-instrument")
        rule.onNodeWithTag("instrument-account-$account_id").performClick()
        wait_text("交易历史")
        rule.onNodeWithText("暂无交易记录").assertIsDisplayed()
        runBlocking { assertTrue(graph.overview.observeSnapshot().first().positions.isEmpty()) }
        window_shot("account-instrument-empty", "添加投资品到账户")
        click_list("添加投资品到账户")
        wait_text("添加投资品到账户")
        save()
        wait_text("交易历史")
        rule.onNodeWithText("暂无交易记录").assertIsDisplayed()
        runBlocking {
            val positions = graph.overview.observeSnapshot().first().positions
            assertEquals(1, positions.size)
            assertEquals(account_id, positions.single().account_id)
            assertEquals(0L, positions.single().holding_quantity_e8)
            assertTrue(graph.investments.trade_page(positions.single().id, null).isEmpty())
        }
        click_list("买入")
        wait_text("份额")
        rule.onNodeWithText("份额").performScrollTo().performTextInput("2")
        rule.onNodeWithText("成交单价").performScrollTo().performTextReplacement("10")
        save()
        wait_text("2 份")
        rule.onNodeWithText("交易历史").assertExists()
        runBlocking {
            val positions = graph.overview.observeSnapshot().first().positions
            assertEquals(1, positions.size)
            assertEquals(account_id, positions.single().account_id)
            assertEquals(200000000L, positions.single().holding_quantity_e8)
        }
        rule.activityRule.scenario.recreate()
        wait_text("2 份")
        rule.onNodeWithText("交易历史").assertExists()
        window_shot("account-instrument-history")
        click_record("trade-record-1")
        wait_text("交易详情")
        rule.onNodeWithText("合成账户 A").assertExists()
        window_shot("account-instrument-trade-detail", "修改买卖记录")
    }

    @Test fun floating_capsule_overlays_full_viewport_and_account_total_opens_holdings() {
        runBlocking {
            graph.settingsWriter.applyChange(SaveFinancialSettings(0,Currency.of("CNY"),
                listOf(FxRate(Currency.of("USD"),Currency.of("CNY"),java.math.BigDecimal("7")))))
            val type = createType("ETF")
            for ((name, price, cost) in listOf(Triple("盈利标的", "120", "100"), Triple("亏损标的", "180", "200"))) {
                val instrument = graph.commands.execute(SaveInstrument(UUID.randomUUID().toString(), null, null,
                    name, if (name == "盈利标的") "GAIN" else "LOSS", type,
                    if (name == "盈利标的") "USD" else "CNY", R.parse_units(price, 5))).id
                val position = graph.commands.execute(CreateInvestmentPosition(UUID.randomUUID().toString(),
                    account_id, instrument)).id
                graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), position,
                    Direction.BUY, R.parse_e8("10"), R.parse_e8(cost), graph.clock.millis(), false))
            }
            val cleared = graph.commands.execute(SaveInstrument(UUID.randomUUID().toString(), null, null,
                "已清仓历史", "CLOSED", type, "CNY", 10000000)).id
            val clearedPosition = graph.commands.execute(CreateInvestmentPosition(UUID.randomUUID().toString(),
                account_id, cleared)).id
            graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), clearedPosition,
                Direction.BUY, R.parse_e8("1"), R.parse_e8("100"), graph.clock.millis(), false))
            graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(), clearedPosition,
                Direction.SELL, R.parse_e8("1"), R.parse_e8("50000"), graph.clock.millis(), false))
        }
        wait_text("合成账户 A")
        val page = rule.onNodeWithTag("accounts-list").fetchSemanticsNode().boundsInWindow
        val capsule = rule.onNodeWithTag("root-capsule").fetchSemanticsNode().boundsInWindow
        assertTrue("List must extend behind floating capsule", page.bottom > capsule.bottom)
        val note = rule.onNode(hasText("仅测试数据") and
            hasAnyAncestor(hasTestTag("account-header-$account_id")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        val cash = rule.onNode(hasText("可用现金 0.00 CNY") and
            hasAnyAncestor(hasTestTag("account-header-$account_id")), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInWindow
        assertEquals(note.top, cash.top, 2f)
        window_shot("floating-account-home")
        rule.onNode(hasText("投资") and hasClickAction()).performClick()
        wait_text("总投资市值"); wait_text("10200.00 CNY")
        rule.onNodeWithTag("investment-market-total").assertTextEquals("10200.00 CNY")
        rule.onNodeWithTag("investment-account-total-$account_id").assertTextEquals("10200.00 CNY")
        rule.onNodeWithTag("account-realized-$account_id").assertTextEquals("已实现盈亏 49900.00 CNY")
        rule.onNodeWithText("已清仓历史").assertDoesNotExist()
        rule.onNodeWithText("查看持仓与记录").assertDoesNotExist()
        rule.onNodeWithText("合成账户 A ▾").performClick()
        wait_text("+20.00%")
        wait_text("-10.00%")
        wait_text("+200.00")
        rule.onNodeWithText("1200.00").assertExists()
        rule.onNodeWithTag("holding-currency-1", useUnmergedTree = true).assertTextEquals("USD")
        rule.onNodeWithTag("holding-currency-2", useUnmergedTree = true).assertTextEquals("CNY")
        wait_text("-200.00")
        rule.onNodeWithTag("holding-3").assertDoesNotExist()
        val floatingBounds = rule.onNodeWithTag("account-floating-$account_id").fetchSemanticsNode().boundsInWindow
        val realizedBounds = rule.onNodeWithTag("account-realized-$account_id").fetchSemanticsNode().boundsInWindow
        assertEquals(floatingBounds.top, realizedBounds.top, 1f)
        assertTrue(realizedBounds.right < floatingBounds.left)
        window_shot("floating-holdings-columns")
        rule.onNodeWithTag("investment-account-total-$account_id").performScrollTo().performClick()
        wait_text("当前持仓")
        rule.onNodeWithTag("root-capsule").assertDoesNotExist()
        wait_text("盈利标的")
        window_shot("account-holdings-without-cards")
    }

    @Test fun input_outlines_align_with_large_fonts_at_two_widths() {
        for((width,height) in listOf(320 to 720,360 to 800,420 to 900,600 to 960,840 to 360))for(scale in listOf(1f,2f)) {
            val density=if(width<=420)2f else 1f
            show_scene("inputs-$width-$scale") {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme {
                        Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.dp).height(height.dp).testTag("inputs")) {
                            FormLayout("参数输入",false,true,{}) {
                                CurrencyChoice("USD",{},options=Currency.supported.map{it.code to it.name})
                                Field("当前余额","1234567.89",{})
                                DateField("记账日期","2026-09-01",{})
                                TimeField("记账时间","15:30",{})
                                CheckboxRow("联动该账户现金",true,{})
                            }
                        }
                        }
                    }
                }
            }
            val currency=rule.onNodeWithTag("input-币种").getUnclippedBoundsInRoot()
            val amount=rule.onNodeWithTag("input-当前余额").getUnclippedBoundsInRoot()
            assertEquals(currency.left.value,amount.left.value,0.1f)
            assertEquals(currency.right.value-currency.left.value,amount.right.value-amount.left.value,0.1f)
            assertEquals(currency.bottom.value-currency.top.value,amount.bottom.value-amount.top.value,0.1f)
            shot("inputs-$width-$scale","inputs",(width*density).toInt())
            rule.onNodeWithTag("checkbox-label-联动该账户现金", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val checkbox = rule.onNodeWithTag("checkbox-control-联动该账户现金", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val checkboxLabel = rule.onNodeWithTag("checkbox-label-联动该账户现金", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals(checkbox.center.y, checkboxLabel.center.y, 1f)
            if (width in listOf(320,840) && scale == 2f) shot("checkbox-alignment-$width", "inputs", (width*density).toInt())
            rule.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun explicit_language_remains_stable_after_platform_recreation() {
        val localeManager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(LocaleManager::class.java)
        val settingsLabel = localized(dev.valnook.app.R.string.nav_settings)
        val baseCurrencyLabel = localized(dev.valnook.feature.settings.R.string.settings_base_currency)
        val languageLabel = localized(dev.valnook.feature.settings.R.string.settings_language)
        val initialLocaleTags = localeManager.applicationLocales.toLanguageTags()
        try {
            wait_text("合成账户 A")
            rule.onNode(hasText(settingsLabel) and hasClickAction()).performClick()
            wait_text(baseCurrencyLabel)
            rule.onNode(hasText(languageLabel) and hasClickAction()).performClick()
            rule.onNodeWithTag("language-option-ENGLISH").performClick()

            rule.waitUntil(15_000) { localeManager.applicationLocales.toLanguageTags() == "en" }
            wait_text("Language")
            rule.onNodeWithTag("language-picker").assertDoesNotExist()
            repeat(10) {
                Thread.sleep(200)
                assertEquals("en", localeManager.applicationLocales.toLanguageTags())
            }
        } finally {
            runBlocking {
                val current = graph.settings.observeSettings().first()
                if (current.language != AppLanguage.SYSTEM) {
                    graph.settingsWriter.applyChange(SaveLanguage(current.revision,AppLanguage.SYSTEM))
                }
            }
            localeManager.applicationLocales = LocaleList.forLanguageTags(initialLocaleTags)
        }
    }
}
