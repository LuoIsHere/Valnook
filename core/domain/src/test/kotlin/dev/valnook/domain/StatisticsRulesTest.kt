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
        // A new install starts with bills first and statistics off the bar.
        assertEquals(NavigationItemId.entries - NavigationItemId.STATISTICS, defaults.visibleInOrder)
        assertEquals(NavigationItemId.BILLS, defaults.order.first())
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

    @Test fun anUpgradeAddsBillsFirstAndOnlyAFullBarGivesUpStatistics() {
        val old = "ACCOUNTS,WALLET,INVESTMENTS,STATISTICS,SETTINGS"
        val full = NavigationConfiguration.restore(old, old)
        assertEquals(NavigationItemId.entries, full.order)
        assertEquals(listOf(NavigationItemId.BILLS, NavigationItemId.ACCOUNTS, NavigationItemId.WALLET,
            NavigationItemId.INVESTMENTS, NavigationItemId.SETTINGS), full.visibleInOrder)
        // Statistics already off the bar stays off; nothing else changes.
        val noStatistics = NavigationConfiguration.restore(old, "ACCOUNTS,WALLET,INVESTMENTS,SETTINGS")
        assertEquals(full.visibleInOrder, noStatistics.visibleInOrder)
        // Another page already off the bar leaves room, so statistics keeps its place.
        val noWallet = NavigationConfiguration.restore("STATISTICS,ACCOUNTS,WALLET,INVESTMENTS,SETTINGS",
            "STATISTICS,ACCOUNTS,INVESTMENTS,SETTINGS")
        assertEquals(listOf(NavigationItemId.BILLS, NavigationItemId.STATISTICS, NavigationItemId.ACCOUNTS,
            NavigationItemId.INVESTMENTS, NavigationItemId.SETTINGS), noWallet.visibleInOrder)
        // Saved again after the upgrade, bills is no longer new and the user's choice stands.
        val saved = NavigationConfiguration.restore("BILLS,ACCOUNTS,WALLET,INVESTMENTS,STATISTICS,SETTINGS",
            "ACCOUNTS,STATISTICS,SETTINGS")
        assertEquals(listOf(NavigationItemId.ACCOUNTS, NavigationItemId.STATISTICS, NavigationItemId.SETTINGS), saved.visibleInOrder)
        assertEquals(NavigationConfiguration(), NavigationConfiguration.restore(null, null))
    }

    @Test fun theBarHoldsAtMostFivePages() {
        assertEquals(5, NavigationConfiguration.MAX_VISIBLE)
        // Every page shown is exactly the limit today: allowed, with no room for another.
        assertFalse(NavigationConfiguration().canShowMore)
        assertTrue(NavigationConfiguration(NavigationItemId.entries, setOf(NavigationItemId.SETTINGS)).canShowMore)
        // The same rule for a smaller limit, as more pages than places will one day exist.
        assertTrue(NavigationConfiguration.canShowMore(2, limit = 3))
        assertFalse(NavigationConfiguration.canShowMore(3, limit = 3))
        assertFalse(NavigationConfiguration.canShowMore(4, limit = 3))
    }
}
