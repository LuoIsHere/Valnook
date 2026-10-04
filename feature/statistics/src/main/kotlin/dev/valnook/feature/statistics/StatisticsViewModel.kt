package dev.valnook.feature.statistics

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.StatisticsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate

data class ChartUiState(
    val metric: StatisticsMetric,
    val period: StatisticsPeriod,
    val series: StatisticsSeries? = null,
    val lastDailyPeriod: StatisticsPeriod = period,
    val followCurrentPeriod: Boolean = true
)

data class StatisticsUiState(
    val today: LocalDate,
    val current: CurrentStatistics? = null,
    val charts: Map<StatisticsMetric, ChartUiState> = emptyMap(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

class StatisticsViewModel(
    private val repository: StatisticsRepository,
    private val clock: Clock,
    private val savedState: SavedStateHandle
) : ViewModel() {
    private val today get() = LocalDate.now(clock)
    private val mutable = MutableStateFlow(StatisticsUiState(today = today,
        charts = StatisticsMetric.entries.associateWith(::restoreChart)))
    val state = mutable.asStateFlow()
    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            repository.observeRevision().distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        refreshJob?.cancel()
        val now = today
        val currentState = state.value
        val charts = currentState.charts.mapValues { (_, chart) ->
            if (!chart.followCurrentPeriod) chart else chart.copy(
                period = currentPeriod(chart.period.granularity, now),
                lastDailyPeriod = StatisticsPeriod(StatisticsGranularity.DAILY, now.year, now.monthValue))
        }
        val snapshot = currentState.copy(today = now, charts = charts)
        charts.values.forEach(::persist)
        mutable.value = snapshot.copy(loading = true, failed = false)
        refreshJob = viewModelScope.launch {
            try {
                val current = repository.loadCurrent()
                val loaded = snapshot.charts.mapValues { (_, chart) ->
                    val series = repository.loadSeries(StatisticsRequest(chart.metric, chart.period))
                    chart.copy(series = series)
                }
                mutable.value = StatisticsUiState(today = snapshot.today, current = current,
                    charts = loaded, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e("ValnookStatistics", "Unable to refresh statistics", error)
                mutable.value = snapshot.copy(loading = false, failed = true)
            }
        }
    }

    fun setGranularity(metric: StatisticsMetric, granularity: StatisticsGranularity) {
        update(metric) { chart ->
            if (chart.period.granularity == granularity) chart else if (granularity == StatisticsGranularity.MONTHLY) {
                chart.copy(period = StatisticsPeriod(StatisticsGranularity.MONTHLY, chart.period.year),
                    lastDailyPeriod = chart.period)
            } else {
                val restored = when {
                    chart.followCurrentPeriod -> currentPeriod(StatisticsGranularity.DAILY, today)
                    chart.lastDailyPeriod.year == chart.period.year -> chart.lastDailyPeriod
                    else -> StatisticsPeriod(StatisticsGranularity.DAILY, chart.period.year,
                        if (chart.period.year == today.year) today.monthValue else 12)
                }
                chart.copy(period = restored, lastDailyPeriod = restored)
            }
        }
    }

    fun previous(metric: StatisticsMetric) = update(metric) { chart ->
        chart.withPeriod(chart.period.previous(), followCurrent = false)
    }
    fun next(metric: StatisticsMetric) = update(metric) { chart ->
        val next = chart.period.next()
        if (next > currentPeriod(chart.period.granularity, today)) chart else
            chart.withPeriod(next, followCurrent = next == currentPeriod(chart.period.granularity, today))
    }
    fun millisUntilNextLocalDay(): Long {
        val next = today.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        return (next - clock.millis() + 100).coerceAtLeast(1_000)
    }

    private fun update(metric: StatisticsMetric, reload: Boolean = true, transform: (ChartUiState) -> ChartUiState) {
        val current = state.value
        val chart = transform(current.charts.getValue(metric))
        persist(chart)
        mutable.value = current.copy(charts = current.charts + (metric to chart))
        if (reload) refresh()
    }

    private fun restoreChart(metric: StatisticsMetric): ChartUiState {
        val now = today
        val prefix = metric.name.lowercase()
        val granularity = savedState.get<String>("$prefix-granularity")?.let {
            runCatching { StatisticsGranularity.valueOf(it) }.getOrNull()
        } ?: StatisticsGranularity.DAILY
        val year = savedState.get<Int>("$prefix-year") ?: now.year
        val month = savedState.get<Int>("$prefix-month")
        val requested = runCatching { StatisticsPeriod(granularity, year, month) }
            .getOrDefault(currentPeriod(granularity, now))
        val period = requested.takeIf { it <= currentPeriod(granularity, now) } ?: currentPeriod(granularity, now)
        val dailyYear = savedState.get<Int>("$prefix-daily-year") ?: now.year
        val dailyMonth = savedState.get<Int>("$prefix-daily-month") ?: now.monthValue
        val lastDaily = runCatching {
            StatisticsPeriod(StatisticsGranularity.DAILY, dailyYear, dailyMonth)
        }.getOrDefault(currentPeriod(StatisticsGranularity.DAILY, now))
        return ChartUiState(metric, period, lastDailyPeriod = lastDaily,
            followCurrentPeriod = savedState["$prefix-follow"] ?: true)
    }

    private fun persist(chart: ChartUiState) {
        val prefix = chart.metric.name.lowercase()
        savedState["$prefix-granularity"] = chart.period.granularity.name
        savedState["$prefix-year"] = chart.period.year
        savedState["$prefix-month"] = chart.period.month
        savedState["$prefix-daily-year"] = chart.lastDailyPeriod.year
        savedState["$prefix-daily-month"] = chart.lastDailyPeriod.month
        savedState["$prefix-follow"] = chart.followCurrentPeriod
    }

    private fun ChartUiState.withPeriod(value: StatisticsPeriod, followCurrent: Boolean) = copy(
        period = value, followCurrentPeriod = followCurrent,
        lastDailyPeriod = if (value.granularity == StatisticsGranularity.DAILY) value else lastDailyPeriod)

    private fun currentPeriod(granularity: StatisticsGranularity, date: LocalDate) = StatisticsPeriod(
        granularity, date.year, date.monthValue.takeIf { granularity == StatisticsGranularity.DAILY })

    private operator fun StatisticsPeriod.compareTo(other: StatisticsPeriod): Int {
        require(granularity == other.granularity)
        return if (year != other.year) year.compareTo(other.year) else (month ?: 0).compareTo(other.month ?: 0)
    }
}
