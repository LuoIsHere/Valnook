package dev.valnook.feature.investments

import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** Which instruments the realized profit page lists, and in what order. All data here is made up. */
class RealizedRowsTest {
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val hkd = Currency.of("HKD")

    private fun instrument(id: Long, symbol: String, currency: Currency) =
        Instrument(id, "Synthetic $symbol", symbol, 1, "Funds", currency, 100_000, true, 1, 0)

    private fun position(id: Long, instrument: Instrument, held: Boolean, realized: String, valid: Boolean = true) =
        Investment(id, 1, 1, "Funds", instrument.name, instrument.symbol, instrument.currency,
            if (held) 100_000_000 else 0, 100_000_000, 0, instrumentId = instrument.id,
            remainingCost = if (held) "1" else "0", realizedProfit = realized, chronologyValid = valid)

    private val sold = instrument(1, "SYNT", cny)
    private val foreign = instrument(2, "DEMO", usd)
    private val noRate = instrument(3, "SYDV", hkd)
    private val small = instrument(4, "BOND", cny)
    private val rounding = instrument(5, "ZERO", cny)
    private val untraded = instrument(6, "NONE", cny)
    private val broken = instrument(7, "BAD", cny)

    private val snapshot = AssetSnapshot(emptyList(), emptyList(), emptyList(), listOf(
        position(11, sold, held = false, realized = "1240"),
        position(12, foreign, held = true, realized = "-86.40"),
        position(13, noRate, held = true, realized = "120"),
        position(14, small, held = false, realized = "-20.10"),
        // The same instrument in a second account adds to the first.
        position(15, small, held = false, realized = "-12"),
        position(16, rounding, held = true, realized = "0.001"),
        position(17, broken, held = true, realized = "5", valid = false)),
        listOf(sold, foreign, noRate, small, rounding, untraded, broken),
        AppSettings(baseCurrency = cny, rates = listOf(FxRate(usd, cny, BigDecimal("7.12")))))

    private val rows = realizedRows(snapshot, AssetValuation.instrumentSummaries(snapshot))

    @Test fun lists_what_realized_something_largest_first_by_base_value() {
        // 1240 CNY, then -86.40 USD (-615.17 CNY), then 120 HKD by its own amount for want of a rate, then -32.10 CNY.
        assertEquals(listOf(1L, 2L, 3L, 4L, 7L), rows.map { it.summary.instrument.id })
    }

    @Test fun leaves_out_what_shows_as_zero_and_what_was_never_traded() {
        assertTrue(rows.none { it.summary.instrument.id == rounding.id || it.summary.instrument.id == untraded.id })
    }

    @Test fun converts_foreign_amounts_only_when_a_rate_is_known() {
        assertEquals(0, BigDecimal("-615.168").compareTo(rows.single { it.summary.instrument.id == foreign.id }.inBase))
        assertNull(rows.single { it.summary.instrument.id == noRate.id }.inBase)
        assertEquals(0, BigDecimal("-32.10").compareTo(rows.single { it.summary.instrument.id == small.id }.summary.realized))
    }

    @Test fun says_whether_each_is_still_held_and_puts_the_unknown_last() {
        assertFalse(rows.first().held)
        assertTrue(rows.single { it.summary.instrument.id == foreign.id }.held)
        assertNull(rows.last().summary.realized)
    }
}
