package dev.valnook.feature.statistics

import dev.valnook.domain.model.Currency
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsFormatTest {
    private val cny = Currency.of("CNY")

    @Test fun amounts_round_half_up_and_group_like_every_other_page() {
        assertEquals("1 234.57 CNY", money(BigDecimal("1234.565"), cny))
        assertEquals("+1 234.57 CNY", money(BigDecimal("1234.565"), cny, signed = true))
        assertEquals("-1 234.57 CNY", money(BigDecimal("-1234.565"), cny, signed = true))
        assertEquals("—", money(null, cny))
    }

    @Test fun what_rounds_to_zero_has_no_sign_and_no_colour() {
        assertEquals("0.00 CNY", money(BigDecimal("0.004"), cny, signed = true))
        assertEquals("0.00 CNY", money(BigDecimal("-0.004"), cny, signed = true))
        assertEquals(0, displayedSign(BigDecimal("0.004"), 2))
        assertEquals(0, displayedSign(BigDecimal("-0.004"), 2))
        assertEquals("+12.35", signedNumber(BigDecimal("12.345"), 2))
    }
}
