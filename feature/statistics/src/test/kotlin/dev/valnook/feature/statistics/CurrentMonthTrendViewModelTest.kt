package dev.valnook.feature.statistics

import androidx.lifecycle.ViewModelStore
import dev.valnook.domain.calculation.StatisticsCalendar
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.StatisticsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.*

@OptIn(ExperimentalCoroutinesApi::class)
class CurrentMonthTrendViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val clock = object : Clock() {
        var now = Instant.parse("2026-10-31T15:59:59Z")
        override fun getZone() = ZoneId.of("Asia/Hong_Kong")
        override fun instant() = now
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }
    private inner class Repository : StatisticsRepository {
        val revision = MutableStateFlow(1L)
        val requests = mutableListOf<StatisticsRequest>()
        var fail = false
        var slowFirst = false
        override fun observeRevision() = revision
        override suspend fun loadCurrent(): CurrentStatistics = error("Home must load only its own metric")
        override suspend fun loadSeries(request: StatisticsRequest): StatisticsSeries {
            requests += request
            val number = requests.size
            if (slowFirst && number == 1) withContext(NonCancellable) { delay(1000) }
            if (fail) throw IllegalStateException("fixture")
            return StatisticsSeries(request.metric, request.period, Currency.of("CNY"),
                StatisticsCalendar.slots(request.period, LocalDate.now(clock)).map {
                    StatisticsPoint(it, BigDecimal(number), reliable = true)
                })
        }
    }
    private fun vm(repository: Repository) = CurrentMonthTrendViewModel(StatisticsMetric.INVESTMENT_VALUE, repository, clock)
        .also { store.put("trend", it) }

    @Test fun loads_only_one_metric_and_refreshes_on_revision() = runTest(dispatcher) {
        val repo = Repository(); val vm = vm(repo); advanceUntilIdle()
        assertEquals(1, repo.requests.size)
        assertEquals(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE, StatisticsPeriod(StatisticsGranularity.DAILY,2026,10)), repo.requests.single())
        repo.revision.value++; advanceUntilIdle()
        assertEquals(2, repo.requests.size)
        assertEquals(BigDecimal(2), vm.state.value.series!!.points.last().value)
    }

    @Test fun month_rollover_clears_the_old_curve_and_loads_new_month() = runTest(dispatcher) {
        val repo = Repository(); val vm = vm(repo); advanceUntilIdle()
        clock.now = clock.now.plusSeconds(2)
        vm.refreshDateIfNeeded()
        assertNull(vm.state.value.series)
        assertEquals(11, vm.state.value.period.month)
        advanceUntilIdle()
        assertEquals(30, vm.state.value.series!!.points.size)
        vm.refreshDateIfNeeded(); advanceUntilIdle()
        assertEquals(2, repo.requests.size)
    }

    @Test fun failed_refresh_retains_same_month_and_retry_recovers() = runTest(dispatcher) {
        val repo = Repository(); val vm = vm(repo); advanceUntilIdle()
        val previous = vm.state.value.series
        repo.fail = true; vm.refresh(); advanceUntilIdle()
        assertTrue(vm.state.value.failed)
        assertEquals(previous, vm.state.value.series)
        repo.fail = false; vm.refresh(); advanceUntilIdle()
        assertFalse(vm.state.value.failed)
        assertFalse(vm.state.value.loading)
    }

    @Test fun delayed_cancelled_response_cannot_replace_new_month() = runTest(dispatcher) {
        val repo = Repository().apply { slowFirst = true }; val vm = vm(repo); runCurrent()
        clock.now = clock.now.plusSeconds(2)
        vm.refreshDateIfNeeded(); advanceUntilIdle()
        assertEquals(11, vm.state.value.series!!.period.month)
        assertEquals(BigDecimal(2), vm.state.value.series!!.points.first().value)
    }
}
