package dev.valnook.domain

import dev.valnook.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.time.*

class CashTrendRulesTest {
    private val zone=ZoneId.of("Asia/Hong_Kong")
    private val now=Instant.parse("2024-03-15T12:00:00Z")
    @Test fun six_months_cross_year_and_leap_day_without_fixed_day_offsets() {
        val febEnd=Instant.parse("2024-02-29T15:59:59.999Z").toEpochMilli()
        val result=CashTrendRules.calculate(150,listOf(CashTrendChange(febEnd,20),CashTrendChange(febEnd+1,30)),0,now,zone)
        assertEquals(listOf("2023-10","2023-11","2023-12","2024-01","2024-02","2024-03"),result.map{it.month.toString()})
        assertEquals(BigInteger.valueOf(100),result[3].balanceMinor)
        assertEquals(BigInteger.valueOf(120),result[4].balanceMinor)
        assertEquals(BigInteger.valueOf(150),result[5].balanceMinor)
    }
    @Test fun missing_history_stays_missing_and_live_point_matches_account_even_with_future_entries() {
        val baseline=Instant.parse("2024-02-12T00:00:00Z").toEpochMilli()
        val result=CashTrendRules.calculate(900,listOf(CashTrendChange(now.plusSeconds(86400*60).toEpochMilli(),100)),baseline,now,zone)
        assertTrue(result.take(4).all{it.balanceMinor==null})
        assertEquals(BigInteger.valueOf(800),result[4].balanceMinor)
        assertEquals(BigInteger.valueOf(900),result.last().balanceMinor)
    }
    @Test fun timezone_changes_month_membership_and_same_millisecond_rows_are_not_lost() {
        val at=Instant.parse("2024-03-01T00:30:00Z").toEpochMilli()
        val changes=listOf(CashTrendChange(at,10),CashTrendChange(at,20))
        assertEquals(BigInteger.valueOf(70),CashTrendRules.calculate(100,changes,0,now,ZoneId.of("UTC"))[4].balanceMinor)
        assertEquals(BigInteger.valueOf(100),CashTrendRules.calculate(100,changes,0,now,ZoneId.of("America/Los_Angeles"))[4].balanceMinor)
    }
    @Test fun reverse_sum_does_not_overflow_long() {
        val values=List(3){CashTrendChange(now.toEpochMilli(),Long.MAX_VALUE)}
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(-2)),CashTrendRules.calculate(Long.MAX_VALUE,values,0,now,zone)[0].balanceMinor)
    }
}
