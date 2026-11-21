package dev.valnook.domain

import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditCycle
import dev.valnook.domain.calculation.CreditCycleSummary
import dev.valnook.domain.model.CreditAccountProfile
import dev.valnook.domain.model.CreditCycleTotals
import dev.valnook.domain.model.CreditDueRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CreditCycleTest {
    private val after20 = CreditDueRule.AfterStatementDays(20)

    @Test fun a_cycle_runs_from_the_day_after_one_statement_through_the_next() {
        val cycle = CreditBillingCalendar.cycle(YearMonth.of(2026, 12), 12, after20)
        assertEquals(CreditCycle(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 12), LocalDate.of(2027, 1, 1)), cycle)
        // Across a year, and with the due date on a fixed day of the next month.
        assertEquals(CreditCycle(LocalDate.of(2026, 12, 13), LocalDate.of(2027, 1, 12), LocalDate.of(2027, 1, 12), LocalDate.of(2027, 2, 5)),
            CreditBillingCalendar.cycle(YearMonth.of(2027, 1), 12, CreditDueRule.FixedDayOfMonth(5)))
    }

    @Test fun a_statement_day_past_the_month_end_falls_on_its_last_day() {
        // 31st: February closes on its last day, and the next cycle starts on March 1st.
        assertEquals(CreditCycle(LocalDate.of(2027, 2, 1), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 20)),
            CreditBillingCalendar.cycle(YearMonth.of(2027, 2), 31, after20))
        assertEquals(LocalDate.of(2027, 3, 1), CreditBillingCalendar.cycle(YearMonth.of(2027, 3), 31, after20).start)
        assertEquals(LocalDate.of(2028, 2, 29), CreditBillingCalendar.cycle(YearMonth.of(2028, 2), 31, after20).statement)
        // 30th: after a 31-day month the cycle starts on the 31st.
        assertEquals(LocalDate.of(2027, 1, 31), CreditBillingCalendar.cycle(YearMonth.of(2027, 2), 30, after20).start)
    }

    @Test fun the_current_cycle_ends_today_on_a_statement_day_and_moves_on_the_day_after() {
        assertEquals(YearMonth.of(2026, 11), CreditBillingCalendar.currentCycle(LocalDate.of(2026, 11, 12), 12, after20).month)
        assertEquals(YearMonth.of(2026, 12), CreditBillingCalendar.currentCycle(LocalDate.of(2026, 11, 13), 12, after20).month)
        assertEquals(YearMonth.of(2027, 1), CreditBillingCalendar.currentCycle(LocalDate.of(2026, 12, 31), 12, after20).month)
        assertEquals(YearMonth.of(2027, 2), CreditBillingCalendar.currentCycle(LocalDate.of(2027, 2, 1), 31, after20).month)
    }

    @Test fun with_the_statement_day_on_the_next_statement_the_cycle_ends_the_day_before() {
        // Statement on the 12th, its own day's spending on the next one: 12 November to 11 December, out on the 12th.
        assertEquals(CreditCycle(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 12, 11), LocalDate.of(2026, 12, 12), LocalDate.of(2027, 1, 1)),
            CreditBillingCalendar.cycle(YearMonth.of(2026, 12), 12, after20, statementDayIncluded = false))
        // On the statement day the new cycle has begun; the day before, the old one is still open.
        assertEquals(YearMonth.of(2026, 12), CreditBillingCalendar.currentCycle(LocalDate.of(2026, 11, 12), 12, after20, false).month)
        assertEquals(YearMonth.of(2026, 11), CreditBillingCalendar.currentCycle(LocalDate.of(2026, 11, 11), 12, after20, false).month)
        // Month ends: the 31st in February starts the next cycle on the 28th; January's starts on 31 January.
        val february = CreditBillingCalendar.cycle(YearMonth.of(2027, 2), 31, after20, false)
        assertEquals(LocalDate.of(2027, 1, 31), february.start)
        assertEquals(LocalDate.of(2027, 2, 27), february.end)
        assertEquals(LocalDate.of(2027, 2, 28), CreditBillingCalendar.cycle(YearMonth.of(2027, 3), 31, after20, false).start)
        // The 1st: the cycle is the calendar month before.
        val first = CreditBillingCalendar.cycle(YearMonth.of(2027, 3), 1, after20, false)
        assertEquals(LocalDate.of(2027, 2, 1) to LocalDate.of(2027, 2, 28), first.start to first.end)
        // Read from the profile.
        val profile = CreditAccountProfile(null, 12, after20, null, statementDayIncluded = false)
        assertEquals(LocalDate.of(2026, 12, 11), CreditBillingCalendar.currentCycle(LocalDate.of(2026, 11, 20), profile).end)
    }

    @Test fun a_cycle_period_covers_whole_days_in_the_zone() {
        val zone = ZoneId.of("Asia/Shanghai")
        val period = CreditBillingCalendar.cycle(YearMonth.of(2026, 12), 12, after20).period(zone)
        assertEquals(LocalDate.of(2026, 11, 13).atStartOfDay(zone).toInstant().toEpochMilli(), period.startMs)
        assertEquals(LocalDate.of(2026, 12, 13).atStartOfDay(zone).toInstant().toEpochMilli(), period.endMs)
        assertEquals(true, period.contains(period.endMs - 1))
        assertEquals(false, period.contains(period.endMs))
    }

    @Test fun the_summary_nets_refunds_and_counts_what_is_left_to_pay() {
        val current = CreditBillingCalendar.cycle(YearMonth.of(2026, 12), 12, after20)
        val previous = CreditBillingCalendar.cycle(YearMonth.of(2026, 11), 12, after20)
        val totals = CreditCycleTotals(spentMinor = 184_260, refundedMinor = 5_800, repaidMinor = 150_000)
        assertEquals(178_460, totals.netSpentMinor)
        val owing = CreditCycleSummary.of(current, totals, previous, balanceAtStatement = -294_380)
        assertEquals(294_380L, owing.previousOwedMinor)
        assertEquals(150_000, owing.previousRepaidMinor)
        assertEquals(144_380L, owing.previousRemainingMinor)
        // Paid more than was owed: nothing left, never below zero.
        assertEquals(0L, CreditCycleSummary.of(current, totals, previous, -100_000).previousRemainingMinor)
        // An overpaid or settled card owed nothing at the statement.
        assertEquals(0L, CreditCycleSummary.of(current, totals, previous, 2_000).previousOwedMinor)
        // Added after that statement: nothing known.
        val unknown = CreditCycleSummary.of(current, totals, previous, null)
        assertNull(unknown.previousOwedMinor)
        assertNull(unknown.previousRemainingMinor)
    }
}
