package dev.valnook.feature.bills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class BillExpressionTest {
    private fun type(keys: String, digits: Int = 2): BillExpression = keys.fold(BillExpression()) { e, c ->
        e.press(when (c) {
            '+' -> BillKey.PLUS
            '-' -> BillKey.MINUS
            '*' -> BillKey.TIMES
            '/' -> BillKey.DIVIDE
            '.' -> BillKey.POINT
            '<' -> BillKey.BACKSPACE
            'C' -> BillKey.CLEAR
            'Z' -> BillKey.D00
            else -> BillKey.valueOf("D$c")
        }, digits)
    }

    @Test fun multiplication_and_division_come_before_addition() {
        assertEquals(BigDecimal("18.00"), type("12+3*2").value(2))
        assertEquals(BigDecimal("60.00"), type("35+12.5*2").value(2))
        assertEquals(BigDecimal("3.33"), type("10/3").value(2))
        assertEquals(BigDecimal("-5.00"), type("5-10").value(2))
        assertNull(type("8/0").value(2))
        assertTrue(type("1+2").hasOperator)
        assertFalse(type("12.5").hasOperator)
        assertFalse(type("12+").hasOperator)
    }

    @Test fun operators_replace_each_other_and_a_trailing_one_is_ignored() {
        assertEquals("3×", type("3+*").text)
        assertEquals(BigDecimal("3.00"), type("3+").value(2))
        assertEquals("", type("+").text)
        assertEquals("4+", type("4.+").text)
    }

    @Test fun decimals_follow_the_currency_and_zeros_do_not_lead() {
        assertEquals("1.25", type("1.257").text)
        assertEquals("12", type("12.", 0).text)
        assertEquals("0.5", type(".5").text)
        assertEquals("5", type("05").text)
        assertEquals("100", type("1Z").text)
        assertEquals("1.20", type("1.2Z").text)
        assertEquals("1", type("12<").text)
        assertEquals("", type("12C").text)
    }

    @Test fun minor_units_and_the_settled_value() {
        assertEquals(1_830L, type("12+3*2.1").minor(2))
        assertEquals("18.3", type("12+3*2.1").settled(2).text)
        assertEquals(206L, type("206", 0).minor(0))
        assertEquals("35.5", BillExpression.of(3_550, 2).text)
    }
}
