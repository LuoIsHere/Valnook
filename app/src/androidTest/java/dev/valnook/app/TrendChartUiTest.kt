package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.*
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.StatisticsCalendar
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.cash.*
import dev.valnook.feature.statistics.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.*
import java.util.Locale
import java.util.UUID

@HiltAndroidTest
class TrendChartUiTest {
    @get:Rule(order=0) val hilt=HiltAndroidRule(this)
    @get:Rule(order=1) val rule=createAndroidComposeRule<MainActivity>()
    private val store=ViewModelStore()
    private val clock=Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneId.of("Asia/Hong_Kong"))
    private val graph get()=rule.activity.sessions.session.value.graph
    @Before fun prepare(){hilt.inject();rule.waitForIdle()}
    @After fun close(){rule.runOnUiThread{store.clear()}}
    private fun capture(name:String) {
        val bitmap=rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("trend-$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
    @Composable private fun PreviewTheme(dark:Boolean,english:Boolean=false,content:@Composable ()->Unit) {
        val config=Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(if(english)Locale.US else Locale.SIMPLIFIED_CHINESE)) }
        val context=LocalContext.current.createConfigurationContext(config)
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides config) {
            ValnookTheme(dark_theme=dark) { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {content()} }
        }
    }
    private fun showCash(dark:Boolean,english:Boolean=false,recent:Boolean=false) {
        val parent=runBlocking {graph.commands.execute(SaveAccount(UUID.randomUUID().toString(),null,null,"Example bank","",emptyList())).id}
        val cash=runBlocking {
            graph.commands.execute(SetCashBalance(UUID.randomUUID().toString(),parent,"CNY",438898,null,
                name=if(english)"Everyday savings"else"人民币长期资金",note=if(english)"Daily balance history"else"日常资金记录"))
            graph.cash.observe_cash(parent).first().single()
        }
        val dates=CashTrendRules.dates(clock.instant(),clock.zone)
        val points=if(recent)CashTrendRules.calculate(438898,emptyList(),clock.millis(),clock.instant(),clock.zone)
            else dates.mapIndexed { index,date -> CashTrendPoint(date,BigInteger.valueOf(
            if(index==dates.lastIndex)438898 else 400000L+index*390L+(kotlin.math.sin(index/9.0)*18000).toLong())) }
        val repo=object:CashRepository by graph.cash {
            override fun observeTrends(accountId:Long,now:Instant,zone:ZoneId)=flowOf(mapOf(cash.id to points))
        }
        rule.runOnUiThread {
            val vm=CashViewModel(parent,repo,graph.cashPages,SavedStateHandle(),clock=clock);store.put("cash",vm)
            rule.activity.setContent { PreviewTheme(dark,english) {
                Column { Text(if(english)"Everyday savings"else"人民币长期资金",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(16.dp))
                    CashDetail(vm,cash.id,{},{}) }
            } }
        }
        rule.waitUntil(10000){rule.onAllNodesWithTag("cash-trend-date").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle()
    }
    @Test fun cash_daily_drag_restores_latest_and_summary_has_no_duplicate_amount() {
        showCash(false)
        val amount=rule.onNodeWithTag("cash-trend-value").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        rule.onAllNodesWithText(amount).assertCountEquals(1)
        val chart=rule.onNodeWithTag("cash-trend-chart")
        val latest=chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString().contains("2026-07-11"))
        capture("cash-light-latest")
        chart.performTouchInput { down(Offset(width*.25f,height*.4f));moveTo(Offset(width*.4f,height*.4f),100) }
        rule.waitForIdle()
        assertNotEquals(latest,chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        assertNotEquals(amount,rule.onNodeWithTag("cash-trend-value").fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        capture("cash-light-selected")
        chart.performTouchInput { up() };rule.waitForIdle()
        assertEquals(latest,chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        rule.onNodeWithTag("cash-trend-value").assertTextEquals(amount)
    }
    @Test fun prehistory_draws_a_zero_line_and_touch_shows_zero_then_restores_latest() {
        showCash(false,recent=true)
        val chart=rule.onNodeWithTag("cash-trend-chart")
        val bitmap=chart.captureToImage().asAndroidBitmap()
        val x=(bitmap.width*.2f).toInt()
        assertTrue("zero history must be rendered as a gold line",(0 until (bitmap.height*.88f).toInt()).any { y ->
            val c=bitmap.getPixel(x,y)
            android.graphics.Color.red(c)-android.graphics.Color.blue(c)>80
        });bitmap.recycle()
        chart.performTouchInput {down(Offset(width*.2f,height*.5f))};rule.waitForIdle()
        rule.onNodeWithTag("cash-trend-value").assertTextEquals("0.00 CNY")
        capture("prehistory-zero-selected")
        chart.performTouchInput {up()};rule.waitForIdle()
        rule.onNodeWithTag("cash-trend-value").assertTextEquals("4 388.98 CNY")
        capture("prehistory-latest")
    }
    @Test fun cash_dark_english_and_vertical_scroll_releases_selection() {
        showCash(true,true)
        val chart=rule.onNodeWithTag("cash-trend-chart")
        val latest=chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        capture("cash-dark-english")
        chart.performTouchInput { swipeUp() };rule.waitForIdle()
        assertEquals(latest,chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
    @Test fun reveal_clips_line_and_area_together_then_touch_splits_gold_and_gray() {
        rule.mainClock.autoAdvance=false
        rule.runOnUiThread {rule.activity.setContent {PreviewTheme(false) {
            var selected by remember { mutableStateOf<Int?>(null) }
            Box(Modifier.padding(24.dp)) {
                TrendChart(List(4){.35f},listOf(0 to "7月",1 to "8月",2 to "9月",3 to "10月"),selected,{selected=it},
                    curvePath={ points -> Path().apply { moveTo(points.first().x,points.first().y);points.drop(1).forEach{lineTo(it.x,it.y)} } },
                    description="Animation fixture",readout=selected?.toString()?:"latest",modifier=Modifier.height(200.dp).testTag("fixture-chart"))
            }
        }}}
        rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
        rule.mainClock.advanceTimeBy(220);rule.waitForIdle()
        val chart=rule.onNodeWithTag("fixture-chart")
        val partial=chart.captureToImage().asAndroidBitmap()
        fun tinted(bitmap:Bitmap,x:Float,y:Float):Boolean {
            val color=bitmap.getPixel((bitmap.width*x).toInt(),(bitmap.height*y).toInt())
            return android.graphics.Color.red(color)-android.graphics.Color.blue(color)>6
        }
        fun hasGoldLine(bitmap:Bitmap,x:Float):Boolean = (0 until (bitmap.height*.8f).toInt()).any { y ->
            val color=bitmap.getPixel((bitmap.width*x).toInt(),y)
            android.graphics.Color.red(color)-android.graphics.Color.blue(color)>80
        }
        capture("reveal-middle")
        assertTrue("left curve must already be revealed",hasGoldLine(partial,.1f))
        assertFalse("right curve must remain clipped",hasGoldLine(partial,.95f))
        assertTrue("left area must already be revealed",tinted(partial,.1f,.6f))
        assertFalse("right area must not appear before reveal",tinted(partial,.95f,.6f))
        partial.recycle()
        rule.mainClock.advanceTimeBy(800);rule.waitForIdle()
        val complete=chart.captureToImage().asAndroidBitmap()
        assertTrue("full area must appear after reveal",tinted(complete,.9f,.6f))
        assertTrue("full curve must appear after reveal",hasGoldLine(complete,.9f));complete.recycle()
        capture("reveal-complete")
        chart.performTouchInput { down(Offset(width*.5f,height*.4f)) }
        rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
        val split=chart.captureToImage().asAndroidBitmap()
        assertTrue(tinted(split,.25f,.6f));assertFalse(tinted(split,.8f,.6f));split.recycle()
        capture("gold-gray-split")
        chart.performTouchInput {up()};rule.mainClock.advanceTimeByFrame()
        chart.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"latest"))
        rule.mainClock.autoAdvance=true
    }
    private fun showStatistics(dark:Boolean,english:Boolean) {
        val repository=object:StatisticsRepository {
            override fun observeRevision()=flowOf(1L)
            override suspend fun loadCurrent()=CurrentStatistics(BigDecimal(125680),BigDecimal(38000),BigDecimal(87680),Currency.of("CNY"),MonthlyAssetChange(null,Currency.of("CNY"),null))
            override suspend fun loadSeries(request:StatisticsRequest):StatisticsSeries {
                val dates=StatisticsCalendar.slots(request.period,LocalDate.now(clock))
                val base=when(request.metric){StatisticsMetric.TOTAL_ASSETS->125000;StatisticsMetric.AVAILABLE_CASH->38000;StatisticsMetric.INVESTMENT_VALUE->87000}
                val points=dates.mapIndexed { index,date ->
                    val future=date>LocalDate.now(clock)
                    val amount=BigDecimal.valueOf(base+index*40+(kotlin.math.sin(index/2.0)*400).toLong())
                    StatisticsPoint(date,amount.takeUnless{future},!future,future,comparisonValue=amount-BigDecimal(120))
                }
                return StatisticsSeries(request.metric,request.period,Currency.of("CNY"),points)
            }
        }
        rule.runOnUiThread {
            val vm=StatisticsViewModel(repository,clock,SavedStateHandle());store.put("statistics",vm)
            rule.activity.setContent {PreviewTheme(dark,english) {StatisticsScreen(vm)}}
        }
        rule.waitUntil(10000){rule.onAllNodesWithTag("statistics-value-total_assets").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle()
    }
    @Test fun future_extension_is_horizontal_dashed_without_area_or_future_selection() {
        rule.runOnUiThread {rule.activity.setContent {PreviewTheme(false) {
            var selected by remember { mutableStateOf<Int?>(null) }
            Box(Modifier.padding(24.dp)) {
                TrendChart(listOf(.7f,.3f,null,null,null),listOf(0 to "1",4 to "31"),selected,{selected=it},
                    curvePath={ points -> Path().apply {moveTo(points.first().x,points.first().y);points.drop(1).forEach {lineTo(it.x,it.y)}} },
                    description="Future extension",readout=selected?.toString()?:"latest",futureExtensionFrom=1,
                    modifier=Modifier.height(200.dp).testTag("future-chart"))
            }
        }}}
        rule.waitForIdle()
        val chart=rule.onNodeWithTag("future-chart")
        val bitmap=chart.captureToImage().asAndroidBitmap()
        val density=rule.activity.resources.displayMetrics.density
        val y=(5*density+(bitmap.height-37*density)*.3f).toInt()
        val reference=bitmap.getPixel((bitmap.width*.8f).toInt(),(bitmap.height*.7f).toInt())
        val samples=((bitmap.width*.45f).toInt() until (bitmap.width*.95f).toInt()).map { x ->
            (y-2..y+2).any { row -> kotlin.math.abs(android.graphics.Color.red(bitmap.getPixel(x,row))-android.graphics.Color.red(reference))>25 }
        }
        assertTrue("future line needs visible dashes",samples.count{it}>30)
        assertTrue("future line must have real gaps",samples.count{!it}>30)
        assertEquals(bitmap.getPixel((bitmap.width*.8f).toInt(),(bitmap.height*.6f).toInt()),reference)
        bitmap.recycle();capture("future-dashed-reference")
        chart.performTouchInput {down(Offset(width*.9f,height*.4f))};rule.waitForIdle()
        chart.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"1"))
        chart.performTouchInput {up()}
    }
    @Test fun statistics_future_extension_keeps_the_latest_amount_and_actual_date() {
        showStatistics(false,false)
        val chart=rule.onNodeWithTag("statistics-chart-total_assets")
        val latest=chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        chart.performTouchInput {down(Offset(width*.9f,height*.4f))};rule.waitForIdle()
        assertEquals(latest,chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        capture("statistics-future-extension")
        chart.performTouchInput {up()}
    }
    @Test fun statistics_light_summary_and_drag_share_one_date_and_restore() {
        showStatistics(false,false)
        val chart=rule.onNodeWithTag("statistics-chart-total_assets")
        val before=chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        rule.onNodeWithTag("statistics-monthly-change").assertDoesNotExist()
        rule.onNodeWithTag("statistics-change-total_assets").assertTextContains("+120",substring=true)
        capture("statistics-light")
        chart.performTouchInput {down(Offset(width*.15f,height*.4f))};rule.waitForIdle()
        assertNotEquals(before,chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        capture("statistics-light-selected")
        chart.performTouchInput {up()};rule.waitForIdle()
        assertEquals(before,chart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
    @Test fun statistics_dark_english_monthly_theme_and_scroll() {
        showStatistics(true,true)
        rule.onNodeWithTag("statistics-granularity-total_assets-monthly").performClick();rule.waitForIdle()
        capture("statistics-dark-monthly")
        rule.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("statistics-chart-investment_value"))
        rule.onNodeWithTag("statistics-change-investment_value").assertExists()
        capture("statistics-dark-investments")
    }
}
