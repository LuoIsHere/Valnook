package dev.valnook.domain

import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.fx.FxHistoryRate
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.MathContext
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class FxRateResolverTest {
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val eur = Currency.of("EUR")
    private val jpy = Currency.of("JPY")
    private val settings = AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.1")),
        FxRate(cny, usd, BigDecimal("0.14")), FxRate(jpy, usd, BigDecimal("0.0067"))))
    private val stored = mapOf("USD" to FxHistoryRate(BigDecimal("0.15"), LocalDate.parse("2026-10-30")),
        "EUR" to FxHistoryRate(BigDecimal("0.128"), LocalDate.parse("2026-10-29")))
    private fun decimal(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun current_rate_then_latest_stored_day_then_missing() {
        val rates = FxRateResolver(settings, FxContext(autoEnabled = true, latest = stored))
        decimal("1", rates.today("CNY"))
        decimal("7.1", rates.today("USD"))                       // current wins over the stored day
        assertEquals(BigDecimal.ONE.divide(BigDecimal("0.128"), MathContext.DECIMAL128), rates.today("EUR"))
        decimal("7.8125", rates.today("EUR"))
        assertNull(rates.today("JPY"))                           // only listed under another base
        assertNull(rates.known("GBP"))
    }

    @Test fun manual_mode_keeps_one_to_one_for_currencies_without_any_rate() {
        val rates = FxRateResolver(settings, FxContext(autoEnabled = false, latest = stored))
        decimal("7.8125", rates.today("EUR"))                    // stored days still count in manual mode
        decimal("1", rates.today("GBP"))
        assertNull(rates.known("GBP"))
        assertNull(FxRateResolver(AppSettings(), FxContext()).today("USD"))
    }

    @Test fun display_rounds_like_typed_rates() {
        val rates = FxRateResolver(settings, FxContext(latest = mapOf("EUR" to FxHistoryRate(BigDecimal("0.13442"), LocalDate.parse("2026-10-28")))))
        assertEquals("7.439369141497", rates.display("EUR")!!.toPlainString())
        assertEquals("7.1", rates.display("USD")!!.toPlainString())
        assertNull(rates.display("GBP"))
    }

    private fun snapshot(fx: FxContext) = AssetSnapshot(listOf(SavingsAccount(1, "A", "")),
        listOf(CashBalance(1, cny, 100_000, 1), CashBalance(1, usd, 10_000, 1), CashBalance(1, eur, 20_000, 1),
            CashBalance(1, Currency.of("KWD"), 1_000, 1)),
        emptyList(), emptyList(), emptyList(), settings, fx = fx)

    @Test fun totals_leave_out_currencies_without_a_rate_in_automatic_mode() {
        val result = AssetValuation.calculate(snapshot(FxContext(autoEnabled = true, latest = stored)))
        // 1000 CNY + 100 USD × 7.1 + 200 EUR × 7.8125; KWD is left out and named.
        decimal("3272.5", result.total.amount)
        assertEquals(setOf(MissingAmount(MissingKind.EXCHANGE_RATE, "KWD")), result.total.missing)
        assertFalse(result.total.complete)
        assertEquals(result.total.missing, result.accounts.single().cash.missing)
    }

    @Test fun manual_mode_totals_are_unchanged_without_stored_rates() {
        val result = AssetValuation.calculate(snapshot(FxContext()))
        // As before: EUR and KWD have no rate and count 1:1 (1000 + 710 + 200 + 1.000).
        decimal("1911", result.total.amount)
        assertTrue(result.total.complete)
    }
}
