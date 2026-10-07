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
