package dev.valnook.domain.model

import java.math.BigDecimal

data class Instrument(val id: Long, val name: String, val symbol: String, val typeId: Long,
    val typeName: String, val currency: Currency, val currentPriceE5: Long,
    val currencyLocked: Boolean, val revision: Long, val priceUpdatedAtMs: Long,
    val symbolLocked: Boolean = false)

data class FxRate(val sourceCurrency: Currency, val targetCurrency: Currency, val rate: BigDecimal)
enum class AppLanguage { SYSTEM, ZH_HANS, ENGLISH }
enum class GainLossColorScheme { GREEN_GAIN, RED_GAIN }
enum class NavigationItemId { ACCOUNTS, INVESTMENTS, STATISTICS, SETTINGS }

data class NavigationConfiguration(
    val order: List<NavigationItemId> = NavigationItemId.entries,
    val visible: Set<NavigationItemId> = NavigationItemId.entries.toSet()
) {
    init {
        require(order.size == NavigationItemId.entries.size && order.toSet() == NavigationItemId.entries.toSet())
        require(NavigationItemId.SETTINGS in visible)
    }

    val visibleInOrder: List<NavigationItemId> get() = order.filter(visible::contains)
    val hiddenInOrder: List<NavigationItemId> get() = order.filterNot(visible::contains)
}

data class AppSettings(val baseCurrency: Currency? = null, val rates: List<FxRate> = emptyList(), val revision: Long = 0,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val gainLossColors: GainLossColorScheme = GainLossColorScheme.GREEN_GAIN,
    val navigation: NavigationConfiguration = NavigationConfiguration())

/** One transactional snapshot. Totals include all accounts, independently of visible pages. */
data class AssetSnapshot(val accounts: List<SavingsAccount>, val cash: List<CashAccount>,
    val deposits: List<TermDeposit>, val positions: List<Investment>,
    val instruments: List<Instrument>, val settings: AppSettings)

enum class MissingKind { BASE_CURRENCY, EXCHANGE_RATE, CURRENT_COST, HISTORICAL_COST, INVALID_HISTORY }
data class MissingAmount(val kind: MissingKind, val currencyCode: String? = null, val positionId: Long? = null)
data class ConvertedTotal(val amount: BigDecimal, val currency: Currency?, val missing: Set<MissingAmount>) {
    val complete: Boolean get() = missing.isEmpty()
}
data class AccountAssets(val account: SavingsAccount, val total: ConvertedTotal, val cash: ConvertedTotal,
    val creditBalance: ConvertedTotal, val depositValue: ConvertedTotal, val investmentValue: ConvertedTotal,
    val floating: ConvertedTotal, val realized: ConvertedTotal, val availableCash: ConvertedTotal = cash)
data class AssetOverview(val accounts: List<AccountAssets>, val total: ConvertedTotal, val cash: ConvertedTotal,
    val creditBalance: ConvertedTotal, val depositValue: ConvertedTotal, val investmentValue: ConvertedTotal,
    val floating: ConvertedTotal, val realized: ConvertedTotal, val availableCash: ConvertedTotal = cash)

data class InstrumentAssets(val instrument: Instrument, val marketValue: BigDecimal,
    val floating: BigDecimal?, val realized: BigDecimal?)
