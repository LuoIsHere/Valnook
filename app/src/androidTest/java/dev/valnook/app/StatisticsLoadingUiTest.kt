package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.StatisticsRepository
import dev.valnook.feature.statistics.StatisticsScreen
import dev.valnook.feature.statistics.StatisticsViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@HiltAndroidTest
class StatisticsLoadingUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val repository = ControlledRepository()
    private lateinit var vm: StatisticsViewModel
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    private val metric = StatisticsMetric.INVESTMENT_VALUE

    @After fun close() { rule.runOnUiThread { store.clear() } }

    private fun open_chart() {
        rule.runOnUiThread {
            vm = StatisticsViewModel(repository,
                Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
            store.put("statistics-loading-test", vm)
            rule.activity.setContent { ValnookTheme(dark_theme = true) { StatisticsScreen(vm) } }
        }
        rule.waitUntil(5_000) { !vm.state.value.loading }
        rule.waitForIdle()
        rule.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("statistics-chart-investment_value"))
        rule.mainClock.autoAdvance = false
    }

    private fun bounds() = rule.onNodeWithTag("statistics-investment_value").fetchSemanticsNode().boundsInRoot

    private fun start_month_change() {
        rule.runOnUiThread { repository.gate = CompletableDeferred() }
        val previousLabel = rule.activity.getString(dev.valnook.feature.statistics.R.string.statistics_previous_month)
        rule.onNode(hasContentDescription(previousLabel) and hasAnyAncestor(hasTestTag("statistics-investment_value")))
            .performClick()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun finish_load() {
        rule.runOnUiThread { repository.gate?.complete(Unit) }
        rule.waitUntil(5_000) { !vm.state.value.loading }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }

    private fun save(name: String) {
        if (!TestShots.enabled) { rule.waitForIdle(); return }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun quick_month_change_does_not_flash_progress_or_move_the_last_card() {
        open_chart()
        val before = bounds()
        start_month_change()
        rule.mainClock.advanceTimeBy(160)
        rule.onAllNodes(progress).assertCountEquals(0)
        assertEquals(before, bounds())
        save("statistics-quick-loading")
        finish_load()
        rule.mainClock.advanceTimeBy(500)
        rule.onAllNodes(progress).assertCountEquals(0)
        assertEquals(before, bounds())
        assertEquals(9, vm.state.value.charts.getValue(metric).series!!.period.month)
        save("statistics-quick-complete")
    }

    @Test fun slow_and_superseded_month_changes_show_only_inline_progress_without_layout_shift() {
        open_chart()
        val before = bounds()
        start_month_change()
        rule.mainClock.advanceTimeBy(400)
        rule.onAllNodes(progress).assertCountEquals(1)
        rule.onNodeWithTag("statistics-loading-investment_value").assertIsDisplayed()
        assertEquals(before, bounds())
        save("statistics-slow-loading")
        // A second click cancels the in-flight request; the latest requested month must win.
        rule.runOnUiThread { vm.previous(metric) }
        rule.mainClock.advanceTimeByFrame()
        assertEquals(before, bounds())
        finish_load()
        rule.onAllNodes(progress).assertCountEquals(0)
        assertEquals(before, bounds())
        assertEquals(8, vm.state.value.charts.getValue(metric).series!!.period.month)
        save("statistics-slow-complete")
    }

    /** Synthetic: 1,000 CNY in one bank and 500 USD (7.2 to the yuan) in another. */
    @Test fun distribution_rows_show_the_share_with_the_base_amount_and_a_currency_its_own() {
        val cny = Currency.of("CNY"); val usd = Currency.of("USD")
        val snapshot = AssetSnapshot(listOf(SavingsAccount(1, "Synthetic bank", ""), SavingsAccount(2, "Synthetic broker", "")),
            listOf(CashAccount(1, cny, 100_000, 1, 11, "Cash"), CashAccount(2, usd, 50_000, 1, 12, "Dollars")),
            emptyList(), emptyList(), emptyList(), AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.2"))), 1))
        val overview = object : dev.valnook.domain.repository.OverviewRepository {
            override fun observeSnapshot() = MutableStateFlow(snapshot)
            override suspend fun snapshot() = snapshot
        }
        rule.runOnUiThread {
            vm = StatisticsViewModel(repository, Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
            val distribution = dev.valnook.feature.statistics.DistributionViewModel(overview)
            store.put("statistics-loading-test", vm); store.put("statistics-distribution-test", distribution)
            rule.activity.setContent { ValnookTheme(dark_theme = false) { StatisticsScreen(vm, distribution = distribution) } }
        }
        rule.waitUntil(5_000) { !vm.state.value.loading }
        rule.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("statistics-distribution-currencies"))
        // USD: 3,600 of 4,600 yuan. Its row says the share, the yuan and the dollars; CNY is not said twice.
        val usdRow = rule.onNodeWithTag("statistics-distribution-currencies-row-0")
        usdRow.assertTextContains("USD").assertTextContains("78.3%").assertTextContains("3 600.00 CNY")
            .assertTextContains("500.00 USD")
        val cnyRow = rule.onNodeWithTag("statistics-distribution-currencies-row-1")
        cnyRow.assertTextContains("21.7%").assertTextContains("1 000.00 CNY")
        rule.onAllNodes(hasText("1 000.00 CNY") and hasAnyAncestor(hasTestTag("statistics-distribution-currencies-row-1")),
            useUnmergedTree = true).assertCountEquals(1)
        // An account row has the yuan only.
        rule.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("statistics-distribution-accounts-row-0"))
        rule.onNodeWithTag("statistics-distribution-accounts-row-0").assertTextContains("Synthetic broker")
            .assertTextContains("3 600.00 CNY")
        rule.onAllNodes(hasText("USD", substring = true) and hasAnyAncestor(hasTestTag("statistics-distribution-accounts")),
            useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("statistics-list").performScrollToNode(hasTestTag("statistics-distribution-currencies"))
        save("statistics-distribution-amounts")
    }

    private class ControlledRepository : StatisticsRepository {
        var gate: CompletableDeferred<Unit>? = null
        override fun observeRevision() = MutableStateFlow(1L)
        override suspend fun loadCurrent(): CurrentStatistics {
            gate?.await()
            return CurrentStatistics(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, Currency.of("CNY"),
                MonthlyAssetChange(BigDecimal.ZERO, Currency.of("CNY"), LocalDate.of(2026, 9, 30)))
        }
        override suspend fun loadSeries(request: StatisticsRequest) = StatisticsSeries(
            request.metric, request.period, Currency.of("CNY"),
            listOf(StatisticsPoint(LocalDate.of(request.period.year, request.period.month ?: 1, 1),
                BigDecimal.ONE, true)))
    }
}
