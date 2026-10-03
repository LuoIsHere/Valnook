package dev.valnook.domain.calculation

import dev.valnook.domain.model.StatisticsGranularity
import dev.valnook.domain.model.StatisticsPeriod
import java.time.LocalDate
import java.time.YearMonth

object StatisticsCalendar {
    fun slots(period: StatisticsPeriod, today: LocalDate): List<LocalDate> = when (period.granularity) {
        StatisticsGranularity.DAILY -> {
            val month = YearMonth.of(period.year, requireNotNull(period.month))
            (1..month.lengthOfMonth()).map(month::atDay)
        }
        StatisticsGranularity.MONTHLY -> (1..12).map { month ->
            val value = YearMonth.of(period.year, month)
            if (value == YearMonth.from(today)) today else value.atEndOfMonth()
        }
    }
}
