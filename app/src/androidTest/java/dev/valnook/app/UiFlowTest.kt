package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.Modifier
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import dev.valnook.app.di.AppGraph
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.designsystem.*
import dev.valnook.feature.accounts.AccountsContent
import dev.valnook.feature.cash.CashContent
import dev.valnook.feature.deposits.DepositsContent
import dev.valnook.feature.investments.InvestmentCard
import java.io.File
import java.time.LocalDate
import java.util.UUID
import android.graphics.Bitmap
import android.view.View
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.ViewAction
import androidx.test.espresso.UiController
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.*
import org.hamcrest.Matcher

@HiltAndroidTest
class UiFlowTest {
    @get:Rule(order=0) val hilt=HiltAndroidRule(this)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    @Inject lateinit var graph:AppGraph
    @Inject lateinit var db:ValnookDatabase
    private var account_id=0L
    @Before fun seed(){hilt.inject();runBlocking{
        account_id=graph.accounts.save_account(null,"合成账户 A","仅测试数据")
        graph.accounts.save_account(null,"合成账户 B","账户隔离")
    };rule.runOnUiThread {assertFalse(rule.activity.window.isNavigationBarContrastEnforced)}}
    private fun wait_text(text:String) {
        try {rule.waitUntil(15000){rule.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}}
        catch(error:androidx.compose.ui.test.ComposeTimeoutException) {
            PlatformTestStorageRegistry.getInstance().openOutputFile("timeout-tree.txt").use{it.write(rule.onRoot().printToString().toByteArray())}
            shot("timeout-state")
            throw error
        }
    }
    private fun save(){
        val focused=rule.onAllNodes(androidx.compose.ui.test.isFocused() and hasSetTextAction())
        if(focused.fetchSemanticsNodes().isNotEmpty())focused[0].performImeAction()
        rule.waitForIdle()
        rule.onNodeWithText("保存").performScrollTo().assertIsDisplayed().performClick()
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
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        rule.onNodeWithText(text).performClick()
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
    @Test fun account_navigation_cash_errors_and_rotation_restoration() {
        wait_text("合成账户 A")
        rule.onNodeWithText("合成账户 A").performClick()
        listOf("现金","定期","投资").forEach{rule.onNodeWithText(it).assertExists()}
        rule.onNodeWithText("添加币种账户").performClick()
        rule.onNodeWithText("当前余额").performTextInput("-1")
        save();wait_text("请检查字段格式");shot("cash-input-error")
        rule.onNodeWithText("当前余额").performTextReplacement("20000.00")
        rule.activityRule.scenario.recreate()
        wait_text("20000.00");rule.onNodeWithText("20000.00").assertExists()
        save();wait_text("20 000.00");shot("cash-saved")
        runBlocking{assertEquals(2000000,db.ledger().cash_one(account_id,"CNY")!!.balance_minor)}
    }
    @Test fun settlement_requires_explicit_cash_checkbox_and_returns_once() {
        runBlocking{graph.commands.execute(OpenTermDeposit(UUID.randomUUID().toString(),account_id,"CNY",1000000,
            R.parse_e8("3"),LocalDate.parse("2026-01-01").toEpochDay(),LocalDate.parse("2026-04-01").toEpochDay(),false))}
        wait_text("合成账户 A");rule.onNodeWithText("合成账户 A").performClick()
        rule.onNodeWithText("定期").performClick();wait_text("已到期，待结算");shot("deposit-matured")
        rule.onNodeWithText("结束存单").performClick()
        rule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        shot("deposit-link-confirmation")
        save();wait_text("已结算")
        rule.onNodeWithText("已结束").assertDoesNotExist()
        rule.onNodeWithText("已结算").performClick();wait_text("已结束");shot("settled-deposits")
        runBlocking{assertEquals(1007397,db.ledger().cash_one(account_id,"CNY")!!.balance_minor)}
        rule.onNodeWithText("结束存单").assertDoesNotExist()
        rule.onNodeWithText("返回").performClick()
        rule.onNodeWithText("现金").performClick();wait_text("CNY · 人民币")
        rule.onNodeWithContentDescription("余额变化 · CNY").performClick()
        wait_text("修改来源存单记录");click_list("修改来源存单记录")
        rule.onNodeWithText("本金").performTextReplacement("20000")
        save();wait_text("+20 147.95 CNY");shot("deposit-source-corrected")
        runBlocking {
            assertEquals(2014795,db.ledger().cash_one(account_id,"CNY")!!.balance_minor)
            assertEquals("CLOSED",db.ledger().deposit(1)!!.status)
            assertEquals(2014795L,db.ledger().source_entry("TERM_CLOSE",1)!!.delta_minor)
        }
    }
    @Test fun investment_trade_price_and_history_are_independent() {
        runBlocking{
            val type=graph.investments.save_type(null,"合成基金")
            graph.commands.execute(CreateInvestment(UUID.randomUUID().toString(),account_id,"合成投资","TEST",type,"CNY",R.parse_e8("10"),R.parse_e8("100"),R.parse_e8("100")))
        }
        wait_text("合成账户 A");rule.onNodeWithText("合成账户 A").performClick()
        rule.onNodeWithText("投资").performClick();wait_text("合成投资")
        rule.onNodeWithText("合成投资").performClick();wait_text("历史盈亏")
        click_list("买入")
        rule.onNodeWithText("成交份额").performTextReplacement("2")
        rule.onNodeWithText("成交单价 · CNY").performTextReplacement("90")
        rule.onNodeWithContentDescription("成交日期:",substring=true).performScrollTo().performClick();native_picker(true)
        rule.onNodeWithContentDescription("记账时间:",substring=true).performScrollTo().performClick();native_picker(false)
        rule.onNode(isToggleable()).performScrollTo().assertIsOff()
        save();wait_text("持有 12 份");shot("investment-trade")
        click_list("更新当前价")
        rule.onNodeWithText("当前每份价格 · CNY").performTextReplacement("120")
        save();wait_text("1 440.00");shot("investment-price")
        runBlocking{
            val rows=db.ledger().first_trades(1,50)
            assertEquals(R.parse_e8("90"),rows.single().execution_price_e8)
            assertNull(db.ledger().cash_one(account_id,"CNY"))
            assertEquals(java.time.LocalDateTime.parse("2026-09-01T15:30").atZone(java.time.ZoneId.of("Asia/Hong_Kong")).toInstant().toEpochMilli(),rows.single().occurred_at_ms)
        }
        click_list("修改买卖记录")
        rule.onNodeWithText("成交份额").performTextReplacement("3")
        rule.onNodeWithText("成交单价 · CNY").performTextReplacement("80")
        save();wait_text("交易历史")
        rule.onNode(hasScrollAction()).performScrollToIndex(0)
        wait_text("持有 13 份");shot("trade-corrected")
        click_list("删除买卖记录")
        rule.onNodeWithText("确认删除").performScrollTo().performClick()
        wait_text("交易历史");rule.onNode(hasScrollAction()).performScrollToIndex(0)
        wait_text("持有 10 份")
        runBlocking{assertTrue(db.ledger().first_trades(1,50).isEmpty());assertEquals(R.parse_e8("120"),db.ledger().investment(1)!!.current_price_e8)}
    }
    @Test fun searchable_multi_currency_accounts_and_cash_source_correction() {
        wait_text("合成账户 A");rule.onNodeWithText("合成账户 A").performClick()
        for((code,amount) in listOf("USD" to "100.00","JPY" to "100","KWD" to "1.234")) {
            click_list("添加币种账户")
            rule.onNodeWithContentDescription("币种:",substring=true).performClick()
            rule.onNodeWithTag("currency-list").performScrollToNode(hasText("TND"))
            rule.onNode(hasText("TND") and !hasSetTextAction()).assertIsDisplayed()
            rule.onNodeWithText("搜索代码或币种名称").performTextInput(if(code=="KWD")"科威特" else code)
            rule.onNode(hasText(code) and !hasSetTextAction()).performClick()
            rule.onNodeWithText("当前余额").performTextInput(amount)
            save();wait_text(code+" · "+Currency.of(code).name)
        }
        shot("multi-currency")
        runBlocking{assertEquals(1234,db.ledger().cash_one(account_id,"KWD")!!.balance_minor);assertEquals(3,graph.cash.observe_cash(account_id).first().size)}
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("USD · 美元"))
        rule.onNode(hasText("修改余额") and hasAnyAncestor(hasContentDescription("余额变化 · USD"))).performClick()
        wait_text("当前余额");rule.onNodeWithText("当前余额").assertTextContains("100.00")
        rule.onNodeWithText("取消").performScrollTo().performClick()
        wait_text("USD · 美元")
        rule.onNodeWithContentDescription("余额变化 · USD").performClick()
        wait_text("手动余额调整");click_list("修改余额变化")
        rule.onNodeWithText("变化金额").performTextReplacement("90.00")
        rule.onNodeWithText("备注").performTextInput("余额调整验证")
        save();wait_text("+90.00 USD");shot("cash-corrected")
        val trade_id=runBlocking {
            val type=graph.investments.save_type(null,"合成基金")
            val asset=graph.commands.execute(CreateInvestment(UUID.randomUUID().toString(),account_id,"来源投资","",type,"USD",0,R.parse_e8("120"))).id
            graph.commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(),asset,Direction.BUY,R.parse_e8("1"),R.parse_e8("10"),graph.clock.millis(),true)).id
        }
        wait_text("修改来源买卖记录");click_list("修改来源买卖记录")
        rule.onNodeWithText("成交份额").performTextReplacement("2")
        save();wait_text("-20.00 USD");shot("cash-source-corrected")
        runBlocking {
            assertEquals(7000,db.ledger().cash_one(account_id,"USD")!!.balance_minor)
            assertEquals(R.parse_e8("2"),db.ledger().trade(trade_id)!!.quantity_e8)
            assertEquals(-2000L,db.ledger().source_entry("TRADE",trade_id)!!.delta_minor)
        }
    }
    @Test fun required_opening_cost_profit_and_closed_portfolio_reentry() {
        runBlocking{graph.investments.save_type(null,"合成基金")}
        wait_text("合成账户 A");rule.onNodeWithText("合成账户 A").performClick()
        rule.onNodeWithText("投资").performClick();click_list("添加投资品")
        rule.onNodeWithText("名称").performTextInput("QQQ")
        rule.onNodeWithText("期初份额").performScrollTo().performTextReplacement("10")
        rule.onNodeWithText("当前每份价格 · CNY").performScrollTo().performTextReplacement("120")
        save();wait_text("请检查字段格式")
        rule.onNodeWithText("期初买入单价 · CNY").performScrollTo().performTextInput("100")
        save();wait_text("QQQ");rule.onNodeWithText("QQQ").performClick();wait_text("历史盈亏")
        click_list("卖出")
        rule.onNodeWithText("成交份额").performTextReplacement("10")
        rule.onNodeWithText("成交单价 · CNY").performTextReplacement("130")
        save();wait_text("持有 0 份")
        rule.onNodeWithText("返回").performClick();wait_text("已清仓")
        rule.onNodeWithText("QQQ").assertDoesNotExist()
        rule.onNodeWithText("已清仓").performClick();wait_text("QQQ");shot("closed-investments")
        rule.onNodeWithText("QQQ").performClick();wait_text("历史盈亏")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("+300.00 CNY"));shot("realized-profit")
        click_list("买入")
        rule.onNodeWithText("成交份额").performTextReplacement("5")
        rule.onNodeWithText("成交单价 · CNY").performTextReplacement("160")
        save();wait_text("持有 5 份")
        runBlocking {
            val profit=graph.investments.observe_profit(1).first()!!
            assertEquals(0,java.math.BigDecimal("120").compareTo(profit.average_cost!!))
            assertEquals("300.00",profit.realized!!.toPlainString())
        }
        rule.onNodeWithText("返回").performClick();wait_text("尚无已清仓的投资项目。")
        rule.onNodeWithText("返回").performClick();wait_text("QQQ")
    }
    @Test fun synthetic_gallery_light_dark_narrow_wide_and_large_text() {
        val a=listOf(SavingsAccount(1,"合成账户 · 很长的账户名称与备注","当前持有的多币种资产"))
        val balances=listOf(CashBalance(1,Currency.of("CNY"),Long.MAX_VALUE,1),CashBalance(1,Currency.of("JPY"),0,1))
        val deposits=listOf(TermDeposit(1,1,Currency.of("CNY"),1000000,R.parse_e8("3"),
            LocalDate.parse("2026-01-01").toEpochDay(),LocalDate.parse("2026-04-01").toEpochDay(),7397,false,true,null))
        val asset=Investment(1,1,1,"合成基金","一项名称非常长的合成投资资产","TEST",Currency.of("USD"),0,0,R.parse_e8("120"),0)
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
                                item{InvestmentCard(asset,{})}
                                item{EmptyState("合成空状态")}
                            }
                        }
                        }
                    }
                }
            }
            shot(name,"gallery",(width*density).toInt())
            show_scene("cash-$name") {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme(dark) {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.width(width.dp).height(height.dp).testTag("cash-cards")) {
                                CashContent(balances,{},{})
                            }
                        }
                    }
                }
            }
            shot("cash-cards-${if(dark)"dark" else "light"}-$width-$scale","cash-cards",(width*density).toInt())
        }
        show_scene("accounts-empty"){ValnookTheme{AccountsContent(emptyList(),{},{})}};shot("accounts-empty")
        show_scene("deposits-gallery"){ValnookTheme{DepositsContent(deposits,LocalDate.parse("2026-04-01").toEpochDay(),{},{})}};shot("deposits-gallery")
    }
    @Test fun input_outlines_align_with_large_fonts_at_two_widths() {
        for((width,height) in listOf(320 to 720,360 to 800,420 to 900,600 to 960,840 to 360))for(scale in listOf(1f,2f)) {
            val density=if(width<=420)2f else 1f
            show_scene("inputs-$width-$scale") {
                CompositionLocalProvider(LocalDensity provides Density(density,scale)) {
                    ValnookTheme {
                        Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.dp).height(height.dp).testTag("inputs")) {
                            FormPanel("参数输入",DraftState(),{},{}) {
                                CurrencyChoice("USD",{},options=Currency.supported.map{it.code to it.name})
                                Field("当前余额","1234567.89",{})
                                DateField("记账日期","2026-09-01",{})
                                TimeField("记账时间","15:30",{})
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
            rule.onNodeWithText("保存").performScrollTo().assertIsDisplayed()
            rule.onNodeWithText("取消").performScrollTo().assertIsDisplayed()
        }
    }
}
