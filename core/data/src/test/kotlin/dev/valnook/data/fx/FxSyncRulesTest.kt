package dev.valnook.data.fx

import dev.valnook.data.fx.FxSyncCoordinator.Companion.due
import dev.valnook.domain.fx.FxSyncTrigger
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FxSyncRulesTest {
    private val today = LocalDate.parse("2026-10-28").toEpochDay()
    private val now = 1_000_000_000L
    private val minute = 60_000L

    @Test fun start_runs_once_a_day_and_waits_thirty_minutes_after_a_failure() {
        assertTrue(due(FxSyncTrigger.START, null, null, null, now, today))
        assertFalse(due(FxSyncTrigger.START, today, now - 5 * minute, null, now, today))
        assertTrue(due(FxSyncTrigger.START, today - 1, now - 5 * minute, null, now, today))
        assertFalse(due(FxSyncTrigger.START, today - 1, now - 29 * minute, "NETWORK", now, today))
        assertTrue(due(FxSyncTrigger.START, today - 1, now - 30 * minute, "NETWORK", now, today))
        // A clock set back counts as the wait having passed.
        assertTrue(due(FxSyncTrigger.START, today - 1, now + 5 * minute, "NETWORK", now, today))
        // A stored day after today (clock set back across midnight) is not today.
        assertTrue(due(FxSyncTrigger.START, today + 1, now - minute, null, now, today))
    }

    @Test fun other_triggers_ignore_the_daily_rule_and_manual_updates_are_spaced() {
        listOf(FxSyncTrigger.ONBOARDING, FxSyncTrigger.BASE_CHANGED, FxSyncTrigger.ENABLED).forEach {
            assertTrue(due(it, today, now - minute, "NETWORK", now, today))
        }
        assertTrue(due(FxSyncTrigger.MANUAL, today, now - 5 * minute, "NETWORK", now, today))
        assertFalse(due(FxSyncTrigger.MANUAL, today, now - 9_999, null, now, today))
        assertTrue(due(FxSyncTrigger.MANUAL, today, now - 10_000, null, now, today))
        assertTrue(due(FxSyncTrigger.MANUAL, today, now + 1_000, null, now, today))
    }

    private fun point(date: String, quote: String, rate: String) = FrankfurterRate(LocalDate.parse(date), quote, BigDecimal(rate))
    private fun day(date: String) = LocalDate.parse(date).toEpochDay()

    @Test fun expansion_fills_each_day_from_the_nearest_earlier_value() {
        val rows = expandDaily("CNY", day("2026-10-24"), day("2026-10-27"), listOf(
            point("2026-10-23", "USD", "0.15"),     // single-day answer for the 24th
            point("2026-10-26", "USD", "0.1501"),
            point("2026-10-24", "EUR", "0.13"),
            point("2026-10-27", "EUR", "1.29E-1"),
            point("2026-10-26", "JPY", "23.5")      // no value before the 26th
        ), 77)
        fun of(quote: String) = rows.filter { it.quote_currency == quote }.map {
            Triple(LocalDate.ofEpochDay(it.local_day).toString(), LocalDate.ofEpochDay(it.source_date).toString(), it.rate) }
        assertEquals(listOf(
            Triple("2026-10-24", "2026-10-23", "0.15"), Triple("2026-10-25", "2026-10-23", "0.15"),
            Triple("2026-10-26", "2026-10-26", "0.1501"), Triple("2026-10-27", "2026-10-26", "0.1501")), of("USD"))
        assertEquals(listOf("0.13", "0.13", "0.13", "0.129"), of("EUR").map { it.third })
        assertEquals(listOf("2026-10-26", "2026-10-27"), of("JPY").map { it.first })
        assertTrue(rows.all { it.base_currency == "CNY" && it.fetched_at_ms == 77L && it.source_date <= it.local_day })
    }

    @Test fun single_day_expansion_and_quotes_without_any_value() {
        val rows = expandDaily("USD", day("2026-10-28"), day("2026-10-28"), listOf(point("2026-10-27", "CNY", "7.1")), 1)
        assertEquals(1, rows.size)
        assertEquals(day("2026-10-27"), rows.single().source_date)
        assertTrue(expandDaily("USD", day("2026-10-28"), day("2026-10-28"), emptyList(), 1).isEmpty())
    }
}
