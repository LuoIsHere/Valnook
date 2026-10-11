package dev.valnook.domain

import dev.valnook.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.*

class CashTrendRulesTest {
    private val zone=ZoneId.of("Asia/Hong_Kong")
    private val now=Instant.parse("2024-03-15T12:00:00Z")
    @Test fun rolling_three_calendar_months_include_every_day_and_both_endpoints() {
        val dates=CashTrendRules.dates(Instant.parse("2026-10-11T12:00:00Z"),zone)
        assertEquals(LocalDate.of(2026,7,11),dates.first())
        assertEquals(LocalDate.of(2026,10,11),dates.last())
        assertEquals(93,dates.size)
        assertTrue(dates.zipWithNext().all { (a,b)->a.plusDays(1)==b })
        assertEquals(LocalDate.of(2024,2,29),CashTrendRules.dates(Instant.parse("2024-05-31T12:00:00Z"),zone).first())
    }
    @Test fun day_end_and_midnight_entries_belong_to_the_correct_day() {
        val febEnd=Instant.parse("2024-02-29T15:59:59.999Z").toEpochMilli()
        val result=CashTrendRules.calculate(150,listOf(CashTrendChange(febEnd,20),CashTrendChange(febEnd+1,30)),0,now,zone).associateBy{it.date}
        assertEquals(BigInteger.valueOf(100),result.getValue(LocalDate.of(2024,2,28)).balanceMinor)
        assertEquals(BigInteger.valueOf(120),result.getValue(LocalDate.of(2024,2,29)).balanceMinor)
        assertEquals(BigInteger.valueOf(150),result.getValue(LocalDate.of(2024,3,1)).balanceMinor)
        assertEquals(result.getValue(LocalDate.of(2024,3,1)).balanceMinor,result.getValue(LocalDate.of(2024,3,14)).balanceMinor)
    }
    @Test fun prehistory_is_zero_and_live_point_matches_account_even_with_future_entries() {
        val baseline=Instant.parse("2024-02-12T00:00:00Z").toEpochMilli()
        val result=CashTrendRules.calculate(900,listOf(CashTrendChange(now.plusSeconds(86400*60).toEpochMilli(),100)),baseline,now,zone)
        assertTrue(result.filter{it.date<LocalDate.of(2024,2,12)}.all{it.balanceMinor==BigInteger.ZERO})
        assertEquals(BigInteger.valueOf(800),result[result.lastIndex-1].balanceMinor)
        assertEquals(BigInteger.valueOf(900),result.last().balanceMinor)
    }
    @Test fun newly_created_accounts_draw_zero_until_the_first_day_and_keep_negative_current_balance() {
        val points=CashTrendRules.calculate(-250,emptyList(),now.toEpochMilli(),now,zone)
        assertTrue(points.dropLast(1).all{it.balanceMinor==BigInteger.ZERO})
        assertEquals(BigInteger.valueOf(-250),points.last().balanceMinor)
        assertTrue(CashTrendRules.calculate(0,emptyList(),now.toEpochMilli(),now,zone).all{it.balanceMinor==BigInteger.ZERO})
    }
    @Test fun timezone_changes_daily_membership_and_same_millisecond_rows_are_not_lost() {
        val at=Instant.parse("2024-03-01T00:30:00Z").toEpochMilli()
        val changes=listOf(CashTrendChange(at,10),CashTrendChange(at,20))
        val day=LocalDate.of(2024,2,29)
        assertEquals(BigInteger.valueOf(70),CashTrendRules.calculate(100,changes,0,now,ZoneId.of("UTC")).single{it.date==day}.balanceMinor)
        assertEquals(BigInteger.valueOf(100),CashTrendRules.calculate(100,changes,0,now,ZoneId.of("America/Los_Angeles")).single{it.date==day}.balanceMinor)
    }
    @Test fun daylight_saving_uses_local_midnight_not_fixed_twenty_four_hours() {
        val zone=ZoneId.of("America/New_York")
        val now=Instant.parse("2024-03-12T12:00:00Z")
        val change=CashTrendChange(Instant.parse("2024-03-11T03:30:00Z").toEpochMilli(),20)
        val result=CashTrendRules.calculate(100,listOf(change),0,now,zone)
        assertEquals(BigInteger.valueOf(80),result.single{it.date==LocalDate.of(2024,3,9)}.balanceMinor)
        assertEquals(BigInteger.valueOf(100),result.single{it.date==LocalDate.of(2024,3,10)}.balanceMinor)
    }
    @Test fun reverse_sum_does_not_overflow_long() {
        val values=List(3){CashTrendChange(now.toEpochMilli(),Long.MAX_VALUE)}
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(-2)),CashTrendRules.calculate(Long.MAX_VALUE,values,0,now,zone)[0].balanceMinor)
    }
    @Test fun ten_thousand_changes_keep_credit_overpayments_and_negative_balances() {
        val at=now.minusSeconds(86400).toEpochMilli()
        val result=CashTrendRules.calculate(50,List(10000){CashTrendChange(at,1)},0,now,zone)
        assertEquals(BigInteger.valueOf(-9950),result.first().balanceMinor)
        assertEquals(BigInteger.valueOf(50),result.last().balanceMinor)
    }
}
