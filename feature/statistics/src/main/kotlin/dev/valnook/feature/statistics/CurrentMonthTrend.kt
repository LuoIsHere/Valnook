package dev.valnook.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.StatisticsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.LocalDate

data class CurrentMonthTrendState(
    val period: StatisticsPeriod,
    val series: StatisticsSeries? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
)

/** One metric for the current month; no history navigation and no loading of other charts.
 * 首页仅订阅当前月份的一个指标；不复用统计页的翻页状态或加载其它图表。 */
class CurrentMonthTrendViewModel(
    val metric: StatisticsMetric,
    private val repository: StatisticsRepository,
    private val clock: Clock,
) : ViewModel() {
    private fun period() = LocalDate.now(clock).let { StatisticsPeriod(StatisticsGranularity.DAILY, it.year, it.monthValue) }
    private val mutable = MutableStateFlow(CurrentMonthTrendState(period()))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var lastDate = LocalDate.now(clock)

    init {
        viewModelScope.launch { repository.observeRevision().distinctUntilChanged().collect { refresh() } }
    }

    fun refresh() {
        job?.cancel()
        val requestId = ++generation
        val target = period()
        lastDate = LocalDate.now(clock)
        mutable.value = CurrentMonthTrendState(target, state.value.series?.takeIf { it.period == target })
        job = viewModelScope.launch {
            try {
                val series = repository.loadSeries(StatisticsRequest(metric, target))
                if (requestId == generation) mutable.value = CurrentMonthTrendState(target, series, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestId == generation) mutable.value = mutable.value.copy(loading = false, failed = true)
            }
        }
    }

    fun refreshDateIfNeeded() { if (LocalDate.now(clock) != lastDate) refresh() }
}

@Composable
fun CurrentMonthTrend(vm: CurrentMonthTrendViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(owner, vm) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { vm.refreshDateIfNeeded(); delay(1_000) }
        }
    }
    val investment = vm.metric == StatisticsMetric.INVESTMENT_VALUE
    val title = stringResource(if (investment) R.string.statistics_home_investment else R.string.statistics_cash)
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)
        .testTag(if (investment) "investment-month-trend" else "accounts-month-trend")) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        key(state.period) {
            InteractiveChart(state.series ?: StatisticsSeries(vm.metric, state.period, null, emptyList()), title,
                showChange = false,
                valueTag = if (investment) "investment-market-total" else "accounts-market-total",
                chartTag = if (investment) "investment-month-chart" else "accounts-month-chart")
        }
        if (state.failed) TextButton(vm::refresh, modifier = Modifier.testTag("home-trend-retry")) {
            Text(stringResource(R.string.statistics_load_failed))
        }
    }
}
