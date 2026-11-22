package dev.valnook.feature.investments

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/** How the holdings page formats amounts and times; the sign rule itself is tested in DecimalRulesTest. */
class HoldingFormatTest {
    @Test fun holding_amounts_are_grouped_like_the_rest_of_the_page() {
        assertEquals("123 456.78", number(BigDecimal("123456.775"), 2))
        assertEquals("-1 234.50", number(BigDecimal("-1234.5"), 2))
        assertEquals("—", number(null, 2))
    }

    @Test fun an_unchanged_time_keeps_its_instant_when_clocks_fall_back() {
        val newYork = java.time.ZoneId.of("America/New_York")
        // 06:30Z on 1 November 2026 is 01:30 the second time round; read as local time it would be 05:30Z.
        val original = java.time.Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        val shown = java.time.Instant.ofEpochMilli(original).atZone(newYork).toLocalDateTime()
        assertEquals(original, keptEpoch(shown, newYork, original))
        // Changed by hand, the new local time is converted as before.
        assertEquals(java.time.Instant.parse("2026-11-01T07:00:00Z").toEpochMilli(),
            keptEpoch(java.time.LocalDateTime.parse("2026-11-01T02:00"), newYork, original))
        assertEquals(java.time.Instant.parse("2026-11-01T05:30:00Z").toEpochMilli(), keptEpoch(shown, newYork, null))
    }
}
