package dev.valnook.domain.model

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

enum class StatisticsMetric { TOTAL_ASSETS, AVAILABLE_CASH, INVESTMENT_VALUE }
enum class StatisticsGranularity { DAILY, MONTHLY }

data class StatisticsPeriod(
    val granularity: StatisticsGranularity,
    val year: Int,
    val month: Int? = null
) {
    init {
        require(granularity == StatisticsGranularity.MONTHLY || month in 1..12)
    }

    fun previous(): StatisticsPeriod = when (granularity) {
        StatisticsGranularity.DAILY -> YearMonth.of(year, requireNotNull(month)).minusMonths(1)
            .let { copy(year = it.year, month = it.monthValue) }
        StatisticsGranularity.MONTHLY -> copy(year = year - 1)
    }

    fun next(): StatisticsPeriod = when (granularity) {
        StatisticsGranularity.DAILY -> YearMonth.of(year, requireNotNull(month)).plusMonths(1)
            .let { copy(year = it.year, month = it.monthValue) }
        StatisticsGranularity.MONTHLY -> copy(year = year + 1)
    }
}

data class StatisticsPoint(
    val date: LocalDate,
    val value: BigDecimal?,
    val reliable: Boolean,
    val future: Boolean = false
)

data class StatisticsSeries(
    val metric: StatisticsMetric,
    val period: StatisticsPeriod,
    val currency: Currency?,
    val points: List<StatisticsPoint>
)

data class MonthlyAssetChange(
    val value: BigDecimal?,
    val currency: Currency?,
    val baselineDate: LocalDate?,
    val reason: String? = null
)

data class CurrentStatistics(
    val totalAssets: BigDecimal?,
    val availableCash: BigDecimal?,
    val investmentValue: BigDecimal?,
    val currency: Currency?,
    val monthlyChange: MonthlyAssetChange
)

data class StatisticsRequest(val metric: StatisticsMetric, val period: StatisticsPeriod)

data class AxisScale(val minimum: BigDecimal, val maximum: BigDecimal, val ticks: List<BigDecimal>)
