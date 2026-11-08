package dev.valnook.feature.accounts

import dev.valnook.domain.model.CurrentStatistics
import dev.valnook.domain.model.MonthlyAssetChange
import dev.valnook.domain.model.StatisticsPoint
import dev.valnook.domain.model.StatisticsRequest
import dev.valnook.domain.model.StatisticsSeries
import dev.valnook.domain.repository.StatisticsRepository
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountTrendViewModelTest {
    private fun point(value: Long) = StatisticsPoint(LocalDate.of(2026, 11, 5), BigDecimal(value), reliable = true)

    private class Repository : StatisticsRepository {
        val revision = MutableStateFlow(1L)
        var result: List<StatisticsPoint> = emptyList()
        var failing = false
        val asked = mutableListOf<Long>()
        override fun observeRevision(): Flow<Long> = revision
        override suspend fun loadSeries(request: StatisticsRequest): StatisticsSeries = error("not used")
        override suspend fun loadCurrent(): CurrentStatistics =
            CurrentStatistics(null, null, null, null, MonthlyAssetChange(null, null, null))
        override suspend fun loadAccountTrend(accountId: Long): List<StatisticsPoint> {
            asked += accountId
            if (failing) error("read failed")
            return result
        }
    }

    private val shown = mutableListOf<Job>()
    /** As the account page does while it is on screen. */
    private fun show(vm: AccountTrendViewModel) { shown += CoroutineScope(Dispatchers.Main).launch { vm.points.collect {} } }

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() { shown.forEach { it.cancel() }; Dispatchers.resetMain() }

    @Test fun reads_its_own_account_and_follows_data_changes() {
        val repository = Repository().apply { result = listOf(point(1)) }
        val vm = AccountTrendViewModel(7, repository)
        show(vm)
        assertEquals(listOf(point(1)), vm.points.value)
        repository.result = listOf(point(2))
        repository.revision.value = 2
        assertEquals(listOf(point(2)), vm.points.value)
        assertEquals(setOf(7L), repository.asked.toSet())
    }

    @Test fun a_page_under_another_reads_nothing_until_it_is_shown_again() {
        val repository = Repository().apply { result = listOf(point(1)) }
        val vm = AccountTrendViewModel(7, repository)
        repository.revision.value = 2
        assertEquals(emptyList<Long>(), repository.asked)
        show(vm)
        assertEquals(listOf(7L), repository.asked)
        shown.removeLast().cancel()
        repository.revision.value = 3
        repository.revision.value = 4
        assertEquals("Hidden: no reads", listOf(7L), repository.asked)
        show(vm)
        assertEquals("Shown again after changes: one read", listOf(7L, 7L), repository.asked)
    }

    @Test fun a_failed_read_keeps_the_last_line() {
        val repository = Repository().apply { result = listOf(point(1)) }
        val vm = AccountTrendViewModel(7, repository)
        show(vm)
        repository.failing = true
        vm.refresh()
        assertEquals(listOf(point(1)), vm.points.value)
        repository.failing = false
        repository.result = listOf(point(3))
        vm.refresh()
        assertEquals(listOf(point(3)), vm.points.value)
    }
}
