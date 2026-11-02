package dev.valnook.domain.fx

import dev.valnook.domain.model.AppSettings
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** A currency's latest stored day: the source's raw value (1 base = [raw] quote) and the date it was published. */
data class FxHistoryRate(val raw: BigDecimal, val sourceDate: LocalDate)

/** Everything rate lookups need besides the settings: the switch and each currency's latest stored day. */
data class FxContext(val autoEnabled: Boolean = false, val latest: Map<String, FxHistoryRate> = emptyMap())

/**
 * Today's rate for converting into the base currency: the current rate in `fx_rates`, else the currency's latest
 * stored day, else none. Every conversion goes through here so home, statistics, web admin and Excel agree.
 */
class FxRateResolver(settings: AppSettings, private val context: FxContext) {
    val base = settings.baseCurrency
    private val current = settings.rates.filter { it.targetCurrency == base }.associate { it.sourceCurrency.code to it.rate }

    /** 1 [code] = result base, from a current or stored rate only; null when neither exists. */
    fun known(code: String): BigDecimal? = when {
        base == null -> null
        code == base.code -> BigDecimal.ONE
        else -> current[code] ?: context.latest[code]?.let { BigDecimal.ONE.divide(it.raw, MathContext.DECIMAL128) }
    }

    /**
     * The rate a total uses; null means the currency is left out and the total marked incomplete. Since v0.0.18 this
     * holds in manual mode too: a currency without any rate is no longer counted at 1:1.
     */
    fun today(code: String): BigDecimal? = known(code)

    /** [known] rounded like the rates users type, for display next to a currency. */
    fun display(code: String): BigDecimal? = known(code)?.setScale(12, RoundingMode.HALF_EVEN)?.stripTrailingZeros()
}
