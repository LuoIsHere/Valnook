package dev.valnook.feature.statistics

import dev.valnook.domain.model.*
import dev.valnook.domain.repository.StatisticsRepository
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-02T12:00:00Z"), ZoneOffset.UTC)
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun chartPeriodsRemainIndependent() = runTest(dispatcher) {
        val vm = StatisticsViewModel(FakeRepository(), clock, SavedStateHandle())
        advanceUntilIdle()
        vm.setGranularity(StatisticsMetric.TOTAL_ASSETS, StatisticsGranularity.MONTHLY)
        vm.previous(StatisticsMetric.AVAILABLE_CASH)
        advanceUntilIdle()
        assertEquals(StatisticsGranularity.MONTHLY,
            vm.state.value.charts.getValue(StatisticsMetric.TOTAL_ASSETS).period.granularity)
        assertEquals(9, vm.state.value.charts.getValue(StatisticsMetric.AVAILABLE_CASH).period.month)
        assertEquals(10, vm.state.value.charts.getValue(StatisticsMetric.INVESTMENT_VALUE).period.month)
    }

    @Test fun granularityRestoresBrowsedMonthAndFutureNavigationIsBlocked() = runTest(dispatcher) {
        val vm = StatisticsViewModel(FakeRepository(), clock, SavedStateHandle())
        advanceUntilIdle()
        vm.previous(StatisticsMetric.TOTAL_ASSETS)
        advanceUntilIdle()
        vm.setGranularity(StatisticsMetric.TOTAL_ASSETS, StatisticsGranularity.MONTHLY)
        advanceUntilIdle()
        vm.setGranularity(StatisticsMetric.TOTAL_ASSETS, StatisticsGranularity.DAILY)
        advanceUntilIdle()
        assertEquals(9, vm.state.value.charts.getValue(StatisticsMetric.TOTAL_ASSETS).period.month)

        vm.next(StatisticsMetric.INVESTMENT_VALUE)
        advanceUntilIdle()
        assertEquals(10, vm.state.value.charts.getValue(StatisticsMetric.INVESTMENT_VALUE).period.month)
    }

    @Test fun chartPeriodRestoresWithoutTransientPointSelection() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = StatisticsViewModel(FakeRepository(), clock, handle)
        advanceUntilIdle()
        first.previous(StatisticsMetric.AVAILABLE_CASH)
        advanceUntilIdle()
        val restored = StatisticsViewModel(FakeRepository(), clock, handle)
        advanceUntilIdle()
        val chart = restored.state.value.charts.getValue(StatisticsMetric.AVAILABLE_CASH)
        assertEquals(9, chart.period.month)
        assertFalse(chart.followCurrentPeriod)
    }

    @Test fun nextDayDelayUsesTheInjectedClockAndLocalDayBoundary() = runTest(dispatcher) {
        val vm = StatisticsViewModel(FakeRepository(), clock, SavedStateHandle())
        advanceUntilIdle()

        assertEquals(43_200_100L, vm.millisUntilNextLocalDay())
    }

    @Test fun axisLabelsUseTwoDecimalsOrOneDecimalWithAnEnglishUnit() {
        assertEquals(listOf("-12.35", "0", "999.99", "1.0k", "1.3M", "-2.5B"), axisLabels(listOf(
            BigDecimal("-12.345"),
            BigDecimal.ZERO,
            BigDecimal("999.99"),
            BigDecimal("1000"),
            BigDecimal("1250000"),
            BigDecimal("-2500000000"),
        )))
        assertEquals("1.0M", compactAxisLabel(BigDecimal("999999")))
        assertEquals(listOf("117.7M", "", "117.8M", "", "117.9M"), axisLabels(listOf(
            BigDecimal("117740000"),
            BigDecimal("117749000"),
            BigDecimal("117750000"),
            BigDecimal("117849000"),
            BigDecimal("117850000"),
        )))
        assertEquals(listOf("117.8M", "", ""), axisLabels(listOf(
            BigDecimal("117774000"),
            BigDecimal("117779000"),
            BigDecimal("117784000"),
        )))
    }

    @Test fun xAxisUsesSparseDayAndMonthLabels() {
        fun series(period: StatisticsPeriod, dates: List<LocalDate>) = StatisticsSeries(
            StatisticsMetric.TOTAL_ASSETS, period, Currency.of("CNY"),
            dates.map { StatisticsPoint(it, BigDecimal.ONE, true) })
        val dailyDates=(1..31).map { LocalDate.of(2026,10,it) }
        assertEquals(listOf("1","8","15","22","31"), sparseXAxisLabels(series(
            StatisticsPeriod(StatisticsGranularity.DAILY,2026,10),dailyDates)).map { it.second })
        val monthlyDates=(1..12).map { LocalDate.of(2026,it,1) }
        assertEquals(listOf("1","4","7","10","12"), sparseXAxisLabels(series(
            StatisticsPeriod(StatisticsGranularity.MONTHLY,2026),monthlyDates)).map { it.second })
    }

    private class FakeRepository : StatisticsRepository {
        private val revision = MutableStateFlow(1L)
        override fun observeRevision(): Flow<Long> = revision
        override suspend fun loadSeries(request: StatisticsRequest): StatisticsSeries = StatisticsSeries(
            request.metric, request.period, Currency.of("CNY"),
            listOf(StatisticsPoint(LocalDate.of(request.period.year, request.period.month ?: 1, 1),
                BigDecimal.ONE, true)))
        override suspend fun loadCurrent(): CurrentStatistics = CurrentStatistics(BigDecimal.ONE,
            BigDecimal.ONE, BigDecimal.ONE, Currency.of("CNY"), MonthlyAssetChange(BigDecimal.ZERO,
                Currency.of("CNY"), LocalDate.of(2026, 9, 30)))
    }
}
