package dev.valnook.domain

import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class StatisticsRulesTest {
    @Test fun calendarSlotsCoverLeapMonthsAndYear() {
        val today = LocalDate.of(2026, 10, 2)
        assertEquals(28, StatisticsCalendar.slots(StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 2), today).size)
        assertEquals(29, StatisticsCalendar.slots(StatisticsPeriod(StatisticsGranularity.DAILY, 2028, 2), today).size)
        assertEquals(30, StatisticsCalendar.slots(StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 4), today).size)
        assertEquals(31, StatisticsCalendar.slots(StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10), today).size)
        assertEquals(12, StatisticsCalendar.slots(StatisticsPeriod(StatisticsGranularity.MONTHLY, 2026), today).size)
    }

    @Test fun periodNavigationUsesMonthOrYear() {
        val daily = StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 1)
        assertEquals(2025, daily.previous().year)
        assertEquals(12, daily.previous().month)
        assertEquals(2027, StatisticsPeriod(StatisticsGranularity.MONTHLY, 2026).next().year)
    }

    @Test fun axisHandlesZeroNegativeConstantAndHugeValues() {
        listOf(listOf("0"), listOf("-20", "-10"), listOf("4", "4"), listOf("1E+30", "2E+30"))
            .forEach { raw ->
                val scale = requireNotNull(StatisticsAxis.scale(raw.map(::BigDecimal)))
                assertTrue(scale.minimum < scale.maximum)
                assertTrue(scale.ticks.size in 5..7)
                raw.map(::BigDecimal).forEach { assertTrue(it >= scale.minimum && it <= scale.maximum) }
            }
    }

    @Test fun smallUpdatesReuseAxisAndOverflowExpandsIt() {
        val first = requireNotNull(StatisticsAxis.scale(listOf(BigDecimal("100"), BigDecimal("110"))))
        assertSame(first, StatisticsAxis.scale(listOf(BigDecimal("101"), BigDecimal("109")), first))
        val expanded = requireNotNull(StatisticsAxis.scale(listOf(BigDecimal("101"), first.maximum + BigDecimal.ONE), first))
        assertTrue(expanded.maximum > first.maximum)
    }

    @Test fun monotoneCurveDoesNotOvershootNeighbourValues() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 10f), CurvePoint(2f, 2f), CurvePoint(3f, 12f))
        MonotoneCurve.segments(points).forEach { segment ->
            val low = minOf(segment.start.y, segment.end.y)
            val high = maxOf(segment.start.y, segment.end.y)
            assertTrue(segment.control1.y in low..high)
            assertTrue(segment.control2.y in low..high)
        }
    }

    @Test fun twoPointCurveIsExactlyLinear() {
        val segment = MonotoneCurve.segments(listOf(CurvePoint(0f, 3f), CurvePoint(6f, 15f))).single()
        assertEquals(CurvePoint(2f, 7f), segment.control1)
        assertEquals(CurvePoint(4f, 11f), segment.control2)
    }

    @Test fun navigationConfigurationRequiresStableCompleteOrderAndSettings() {
        val defaults = NavigationConfiguration()
        assertEquals(NavigationItemId.entries, defaults.visibleInOrder)
        val onlySettings = NavigationConfiguration(NavigationItemId.entries, setOf(NavigationItemId.SETTINGS))
        assertEquals(listOf(NavigationItemId.SETTINGS), onlySettings.visibleInOrder)
        assertThrows(IllegalArgumentException::class.java) {
            NavigationConfiguration(NavigationItemId.entries, setOf(NavigationItemId.ACCOUNTS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationConfiguration(listOf(NavigationItemId.SETTINGS), setOf(NavigationItemId.SETTINGS))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NavigationConfiguration(listOf(NavigationItemId.ACCOUNTS, NavigationItemId.ACCOUNTS,
                NavigationItemId.STATISTICS, NavigationItemId.SETTINGS), setOf(NavigationItemId.SETTINGS))
        }
    }
}
