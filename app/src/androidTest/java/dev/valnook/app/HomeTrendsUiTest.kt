package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.*
import dev.valnook.app.di.DemoDataSeeder
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal
import java.util.Locale

@HiltAndroidTest
class HomeTrendsUiTest {
    @get:Rule(order=0) val hilt = HiltAndroidRule(this)
    @get:Rule(order=1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject() }

    private fun capture(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("home-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
        }
        bitmap.recycle()
    }
    private fun text(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].single().text
    private fun waitForAmount(tag: String) {
        rule.waitUntil(15_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()
                ?.config?.get(SemanticsProperties.Text)?.singleOrNull()?.text?.contains("CNY") == true
        }
        rule.waitForIdle()
    }
    private fun verify(dark: Boolean) {
        val activity = rule.activity
        val graph = activity.sessions.session.value.graph
        runBlocking { DemoDataSeeder(graph, graph.clock).seed(AppSettings()) }
        val overview = runBlocking { AssetValuation.calculate(graph.overview.snapshot()) }
        val config = Configuration(activity.resources.configuration).apply {
            setLocales(LocaleList(if (dark) Locale.US else Locale.SIMPLIFIED_CHINESE))
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val context = activity.createConfigurationContext(config)
        rule.runOnUiThread { activity.setContent {
            CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context) {
                ValnookRoot(activity.sessions, activity.webAdmin)
            }
        } }
        val theme = if (dark) "dark-en" else "light-zh"
        waitForAmount("accounts-market-total")
        assertEquals(0, BigDecimal(text("accounts-market-total").removeSuffix(" CNY").replace(" ","")).compareTo(overview.availableCash.amount.setScale(2, java.math.RoundingMode.HALF_UP)))
        rule.onNodeWithTag("accounts-summary").assertDoesNotExist()
        rule.onNodeWithTag("root-toolbar-background").assertDoesNotExist()
        rule.onAllNodesWithTag("statistics-granularity-available_cash").assertCountEquals(0)
        capture("$theme-accounts")
        val chart = rule.onNodeWithTag("accounts-month-chart")
        val original = text("accounts-market-total")
        chart.performTouchInput { down(Offset(width*.02f,height*.4f)) }
        rule.waitForIdle()
        capture("$theme-accounts-selected")
        chart.performTouchInput { up() }; rule.waitForIdle()
        assertEquals(original, text("accounts-market-total"))

        rule.onNodeWithTag("nav-investments").performClick()
        waitForAmount("investment-market-total")
        assertEquals(0, BigDecimal(text("investment-market-total").removeSuffix(" CNY").replace(" ","")).compareTo(overview.investmentValue.amount.setScale(2, java.math.RoundingMode.HALF_UP)))
        rule.onNodeWithTag("investment-summary").assertDoesNotExist()
        rule.onAllNodesWithTag("statistics-granularity-investment_value").assertCountEquals(0)
        capture("$theme-investments")
        val investmentChart = rule.onNodeWithTag("investment-month-chart")
        val latest = investmentChart.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        investmentChart.performTouchInput { down(Offset(width*.9f,height*.4f)) }; rule.waitForIdle()
        assertEquals(latest, investmentChart.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        investmentChart.performTouchInput { up() }

        rule.onNodeWithTag("nav-statistics").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("statistics-summary").fetchSemanticsNodes().isNotEmpty() }
        capture("$theme-statistics-summary")
        rule.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("statistics-chart-total_assets"))
        rule.onNodeWithTag("statistics-granularity-total_assets-monthly").performClick()
        rule.waitForIdle()
        capture("$theme-statistics-monthly")
        rule.onNodeWithTag("nav-accounts").performClick()
        waitForAmount("accounts-market-total")
        assertEquals(original, text("accounts-market-total"))
        rule.onNodeWithTag("accounts-month-chart").assertExists()
    }
    @Test fun light_chinese_home_charts_and_moved_summary() = verify(false)
    @Test fun dark_english_home_charts_and_moved_summary() = verify(true)
}
