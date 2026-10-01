package dev.valnook.domain.model

import java.math.BigDecimal

data class Instrument(val id: Long, val name: String, val symbol: String, val typeId: Long,
    val typeName: String, val currency: Currency, val currentPriceE5: Long,
    val currencyLocked: Boolean, val revision: Long, val priceUpdatedAtMs: Long)

data class FxRate(val sourceCurrency: Currency, val targetCurrency: Currency, val rate: BigDecimal)
data class AppSettings(val baseCurrency: Currency? = null, val rates: List<FxRate> = emptyList(), val revision: Long = 0)

/** One transactional snapshot. Totals include all accounts, independently of visible pages. */
data class AssetSnapshot(val accounts: List<SavingsAccount>, val cash: List<CashBalance>,
    val deposits: List<TermDeposit>, val positions: List<Investment>,
    val instruments: List<Instrument>, val settings: AppSettings)

enum class MissingKind { BASE_CURRENCY, EXCHANGE_RATE, CURRENT_COST, HISTORICAL_COST, INVALID_HISTORY }
data class MissingAmount(val kind: MissingKind, val currencyCode: String? = null, val positionId: Long? = null)
data class ConvertedTotal(val amount: BigDecimal, val currency: Currency?, val missing: Set<MissingAmount>) {
    val complete: Boolean get() = missing.isEmpty()
}
data class AccountAssets(val account: SavingsAccount, val total: ConvertedTotal, val cash: ConvertedTotal,
    val investmentValue: ConvertedTotal, val floating: ConvertedTotal, val realized: ConvertedTotal)
data class AssetOverview(val accounts: List<AccountAssets>, val total: ConvertedTotal, val cash: ConvertedTotal,
    val investmentValue: ConvertedTotal, val floating: ConvertedTotal, val realized: ConvertedTotal)

data class InstrumentAssets(val instrument: Instrument, val marketValue: BigDecimal,
    val floating: BigDecimal?, val realized: BigDecimal?)
