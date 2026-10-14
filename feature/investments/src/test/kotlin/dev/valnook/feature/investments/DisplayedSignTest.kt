package dev.valnook.feature.investments

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayedSignTest {
    @Test fun residue_that_rounds_to_zero_is_neutral() {
        assertEquals(0, displayedSign(BigDecimal("-0.000154"), 2))
        assertEquals(0, displayedSign(BigDecimal("0.004999"), 2))
        assertEquals(0, displayedSign(BigDecimal("-0.003"), 2))
        assertEquals(0, displayedSign(null, 2))
    }

    @Test fun values_that_display_as_non_zero_keep_their_sign() {
        assertEquals(1, displayedSign(BigDecimal("0.005"), 2))
        assertEquals(-1, displayedSign(BigDecimal("-0.005"), 2))
        assertEquals(-1, displayedSign(BigDecimal("-0.10"), 2))
        assertEquals(1, displayedSign(BigDecimal("0.5"), 0))
        assertEquals(0, displayedSign(BigDecimal("0.4"), 0))
    }
}
