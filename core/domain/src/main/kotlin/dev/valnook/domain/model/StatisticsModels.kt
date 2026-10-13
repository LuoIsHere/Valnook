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
    val future: Boolean = false,
    val comparisonValue: BigDecimal? = null
) {
    // A missing comparison (before history starts) counts as zero.
    // 没有对比数据（如历史起点之前）按 0 处理。
    val change: BigDecimal? get() = if (reliable && !future) value?.subtract(comparisonValue ?: BigDecimal.ZERO) else null
    // From zero: ±100% by direction, 0.00% when unchanged. Otherwise relative to |comparison| so the sign follows the change.
    // 从 0 开始：按方向记 ±100%，不变记 0.00%；否则以对比值的绝对值为基数，正负号跟随变化方向。
    val changePercent: BigDecimal? get() = change?.let { delta ->
        val base = comparisonValue?.abs() ?: BigDecimal.ZERO
        if (base.signum() == 0) BigDecimal(100 * delta.signum()).setScale(2)
        else delta.multiply(BigDecimal(100)).divide(base, 2, java.math.RoundingMode.HALF_UP)
    }
}

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
