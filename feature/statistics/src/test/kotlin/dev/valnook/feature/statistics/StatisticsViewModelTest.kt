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

    @Test fun chartDraftRestoresFromSavedState() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val first = StatisticsViewModel(FakeRepository(), clock, handle)
        advanceUntilIdle()
        first.previous(StatisticsMetric.AVAILABLE_CASH)
        first.select(StatisticsMetric.AVAILABLE_CASH, 0)
        advanceUntilIdle()
        val restored = StatisticsViewModel(FakeRepository(), clock, handle)
        advanceUntilIdle()
        val chart = restored.state.value.charts.getValue(StatisticsMetric.AVAILABLE_CASH)
        assertEquals(9, chart.period.month)
        assertEquals(0, chart.selectedIndex)
        assertFalse(chart.followCurrentPeriod)
    }

    @Test fun nextDayDelayUsesTheInjectedClockAndLocalDayBoundary() = runTest(dispatcher) {
        val vm = StatisticsViewModel(FakeRepository(), clock, SavedStateHandle())
        advanceUntilIdle()

        assertEquals(43_200_100L, vm.millisUntilNextLocalDay())
    }

    @Test fun compactAxisLabelsStayDistinctForLargeValuesInANarrowRange() {
        val labels = axisLabels(listOf(
            BigDecimal("117555000"),
            BigDecimal("117557500"),
            BigDecimal("117560000"),
            BigDecimal("117562500"),
            BigDecimal("117565000"),
        ))

        assertEquals(labels.size, labels.distinct().size)
        assertTrue(labels.all { it.endsWith("M") })
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
