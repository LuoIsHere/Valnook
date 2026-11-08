package dev.valnook.feature.statistics

import dev.valnook.domain.model.*
import dev.valnook.domain.repository.StatisticsRepository
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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

    @Test fun xAxisUsesSparseDayAndMonthLabels() {
        fun series(period: StatisticsPeriod, dates: List<LocalDate>) = StatisticsSeries(
            StatisticsMetric.TOTAL_ASSETS, period, Currency.of("CNY"),
            dates.map { StatisticsPoint(it, BigDecimal.ONE, true) })
        val dailyDates=(1..31).map { LocalDate.of(2026,10,it) }
        val daily=series(StatisticsPeriod(StatisticsGranularity.DAILY,2026,10),dailyDates)
        assertEquals(listOf("1","8","15","22","31"), sparseXAxisIndexes(daily).map { daily.points[it].date.dayOfMonth.toString() })
        val monthlyDates=(1..12).map { LocalDate.of(2026,it,1) }
        val monthly=series(StatisticsPeriod(StatisticsGranularity.MONTHLY,2026),monthlyDates)
        assertEquals(listOf("1","4","7","10","12"), sparseXAxisIndexes(monthly).map { monthly.points[it].date.monthValue.toString() })
    }

    @Test fun a_page_left_in_the_back_stack_does_not_reload_on_every_change() = runTest(dispatcher) {
        val repository = FakeRepository()
        val vm = StatisticsViewModel(repository, clock, SavedStateHandle())
        repository.revision.value = 2
        advanceUntilIdle()
        assertEquals("Not shown yet: nothing loads", 0, repository.loads)

        var shown = launch { vm.state.collect {} }
        advanceUntilIdle()
        // The current values and one series per metric.
        assertEquals(4, repository.loads)
        repository.revision.value = 3
        advanceUntilIdle()
        assertEquals(8, repository.loads)

        shown.cancel()
        advanceUntilIdle()
        repeat(3) { repository.revision.value += 1; advanceUntilIdle() }
        assertEquals("Hidden: changes load nothing", 8, repository.loads)

        shown = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("Back after changes: one load", 12, repository.loads)
        shown.cancel()
        advanceUntilIdle()
        shown = launch { vm.state.collect {} }
        advanceUntilIdle()
        assertEquals("Back without changes: no load", 12, repository.loads)
        assertFalse(vm.state.value.loading)
        shown.cancel()
    }

    private class FakeRepository : StatisticsRepository {
        val revision = MutableStateFlow(1L)
        var loads = 0
        override fun observeRevision(): Flow<Long> = revision
        override suspend fun loadSeries(request: StatisticsRequest): StatisticsSeries = StatisticsSeries(
            request.metric.also { loads++ }, request.period, Currency.of("CNY"),
            listOf(StatisticsPoint(LocalDate.of(request.period.year, request.period.month ?: 1, 1),
                BigDecimal.ONE, true)))
        override suspend fun loadCurrent(): CurrentStatistics = CurrentStatistics(BigDecimal.ONE.also { loads++ },
            BigDecimal.ONE, BigDecimal.ONE, Currency.of("CNY"), MonthlyAssetChange(BigDecimal.ZERO,
                Currency.of("CNY"), LocalDate.of(2026, 9, 30)))
    }
}
