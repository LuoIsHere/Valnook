package dev.valnook.domain

import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.model.*
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class DecimalRulesTest {
    @Test fun three_digit_currency_and_signed_replacement_avoid_intermediate_overflow() {
        val kwd=Currency.of("KWD")
        assertEquals(1234L,R.parse_minor("1.234",kwd))
        assertEquals(1235L,R.amount(R.parse_e8("1"),R.parse_e8("1.2345"),kwd,true))
        error(ErrorCode.PRECISION){R.parse_minor("1.2345",kwd)}
        assertEquals(Long.MAX_VALUE,R.replace_contribution(Long.MAX_VALUE,-1,-1))
        error(ErrorCode.OVERFLOW){R.replace_contribution(Long.MAX_VALUE,-1,0)}
        assertEquals("-1 234.56",R.format_display(-123456,2))
        assertEquals(54,Currency.supported.size)
    }
    private val cny = Currency.of("CNY")
    private fun day(s: String) = LocalDate.parse(s).toEpochDay()
    private fun error(code: ErrorCode, block: () -> Unit) {
        try { block(); fail("expected $code") } catch(e: DomainException) { assertEquals(code, e.code) }
    }
    @Test fun ninety_day_interest_and_progress() {
        val a = day("2026-01-01"); val b = day("2026-04-01")
        assertEquals(90, R.days(a,b)); assertEquals(7397, R.interest(1000000, R.parse_e8("3"), a,b))
        assertEquals(1007397, R.add(1000000,7397))
        assertEquals("0.00000000", R.progress(a,b,a-1).toPlainString())
        assertEquals("0.50000000", R.progress(a,b,day("2026-02-15")).toPlainString())
        assertEquals("1.00000000", R.progress(a,b,b+30).toPlainString())
    }
    @Test fun currency_precision_and_half_up() {
        assertEquals(101, R.amount(R.parse_e8("1"),R.parse_e8("1.005"),cny))
        assertEquals(2, R.amount(R.parse_e8("1"),R.parse_e8("1.5"),Currency.of("JPY")))
        assertEquals(10000, R.parse_minor("100.00", cny))
        assertEquals(100, R.parse_minor("100", Currency.of("JPY")))
        error(ErrorCode.PRECISION) { R.parse_minor("1.0", Currency.of("JPY")) }
        error(ErrorCode.PRECISION) { R.parse_e8("1.000000001") }
        assertEquals(1, R.parse_e8("0.00000001"))
    }
    @Test fun malformed_negative_and_zero() {
        listOf("-1","NaN","Infinity","1e3","1,000","","1.").forEach {
            error(ErrorCode.FORMAT) { R.parse_e8(it) }
        }
        error(ErrorCode.POSITIVE) { R.parse_e8("0",true) }
        error(ErrorCode.AMOUNT_TOO_SMALL) { R.amount(1,1,cny,true) }
        assertEquals(0, R.parse_minor("0",cny))
        assertEquals(0, R.amount(0,0,cny))
    }
    @Test fun long_limits_and_intermediate_product() {
        assertEquals(Long.MAX_VALUE, R.parse_units("92233720368547758.07",2))
        error(ErrorCode.OVERFLOW) { R.parse_units("92233720368547758.08",2) }
        error(ErrorCode.OVERFLOW) { R.add(Long.MAX_VALUE,1) }
        error(ErrorCode.OVERFLOW) { R.amount(Long.MAX_VALUE,Long.MAX_VALUE,cny) }
        // Intermediate Long multiplication would overflow; decimal calculation remains correct.
        assertEquals(10000000000, R.amount(R.parse_e8("10000"),R.parse_e8("10000"),cny))
    }
    @Test fun leap_year_fixed_365_and_zero_rate() {
        assertEquals(36600, R.interest(3650000,R.parse_e8("1"),day("2024-01-01"),day("2025-01-01")))
        assertEquals(0, R.interest(10000,0,day("2026-01-01"),day("2026-01-02")))
        error(ErrorCode.DATE) { R.interest(100,0,day("2026-01-01"),day("2026-01-01")) }
    }
    @Test fun picker_round_trip_is_timezone_independent() {
        listOf("2024-02-29","2026-01-01","2026-12-31").forEach {
            val d = day(it); assertEquals(d,R.picker_to_epoch_day(R.epoch_day_to_picker(d)))
        }
    }
    @Test fun trade_examples_and_valuation() {
        assertEquals(18000,R.amount(R.parse_e8("2"),R.parse_e8("90"),cny,true))
        assertEquals(33000,R.amount(R.parse_e8("3"),R.parse_e8("110"),cny,true))
        assertEquals(108000,R.amount(R.parse_e8("9"),R.parse_e8("120"),cny))
    }
}
