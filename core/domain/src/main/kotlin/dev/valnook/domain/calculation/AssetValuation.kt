package dev.valnook.domain.calculation

import dev.valnook.domain.model.*
import java.math.BigDecimal

/** Aggregate exact source units. Rounding belongs to presentation. */
object AssetValuation {
    fun instrumentSummaries(snapshot: AssetSnapshot): List<InstrumentAssets> {
        val grouped = snapshot.positions.groupBy { it.instrumentId }
        fun sumKnown(values: List<BigDecimal?>): BigDecimal? =
            if (values.any { it == null }) null else values.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add)
        return snapshot.instruments.map { instrument ->
            val positions = grouped[instrument.id].orEmpty()
            val profits = positions.map(InvestmentProfitCalculator::fromReadModel)
            InstrumentAssets(instrument, positions.map(::marketValue).fold(BigDecimal.ZERO, BigDecimal::add),
                sumKnown(profits.map { it.unrealized }), sumKnown(profits.map { it.realized }))
        }
    }

    fun calculate(snapshot: AssetSnapshot): AssetOverview {
        val cashByAccount = snapshot.cash.groupBy { it.account_id }
        val depositsByAccount = snapshot.deposits.filterNot { it.closed }.groupBy { it.account_id }
        val positionsByAccount = snapshot.positions.groupBy { it.account_id }
        val accounts = snapshot.accounts.map { account ->
            val cashAmounts = cashByAccount[account.id].orEmpty().map {
                it.currency to BigDecimal.valueOf(it.balance_minor, it.currency.fraction_digits)
            }
            val deposits = depositsByAccount[account.id].orEmpty().map {
                it.currency to BigDecimal.valueOf(it.principal_minor, it.currency.fraction_digits)
            }
            val positions = positionsByAccount[account.id].orEmpty()
            val values = positions.map { it.currency to marketValue(it) }
            val floating = positions.filter { it.holding_quantity_e8 > 0 }.map {
                val profit = InvestmentProfitCalculator.fromReadModel(it)
                it.currency to (profit.unrealized ?: BigDecimal.ZERO)
            }
            val floatingMissing = positions.filter { it.holding_quantity_e8 > 0 && (it.remainingCost == null || !it.chronologyValid ||
                it.algorithmVersion != InvestmentProfitCalculator.ALGORITHM_VERSION) }
                .map { MissingAmount(if (it.chronologyValid) MissingKind.CURRENT_COST else MissingKind.INVALID_HISTORY,
                    it.currency.code, it.id) }.toSet()
            val realized = positions.map { it.currency to (InvestmentProfitCalculator.fromReadModel(it).realized ?: BigDecimal.ZERO) }
            val realizedMissing = positions.filter { it.realizedProfit == null || !it.chronologyValid ||
                it.algorithmVersion != InvestmentProfitCalculator.ALGORITHM_VERSION }
                .map { MissingAmount(if (it.chronologyValid) MissingKind.HISTORICAL_COST else MissingKind.INVALID_HISTORY,
                    it.currency.code, it.id) }.toSet()
            AccountAssets(account, convert(cashAmounts + deposits + values, snapshot.settings),
                convert(cashAmounts, snapshot.settings), convert(values, snapshot.settings),
                convert(floating, snapshot.settings, floatingMissing), convert(realized, snapshot.settings, realizedMissing))
        }
        fun total(select: (AccountAssets) -> ConvertedTotal): ConvertedTotal = ConvertedTotal(
            accounts.fold(BigDecimal.ZERO) { result, account -> result.add(select(account).amount) },
            snapshot.settings.baseCurrency,
            accounts.flatMap { select(it).missing }.toSet() +
                if (snapshot.settings.baseCurrency == null) setOf(MissingAmount(MissingKind.BASE_CURRENCY)) else emptySet())
        return AssetOverview(accounts, total { it.total }, total { it.cash }, total { it.investmentValue },
            total { it.floating }, total { it.realized })
    }

    fun marketValue(position: Investment): BigDecimal = BigDecimal.valueOf(position.holding_quantity_e8, 8)
        .multiply(BigDecimal.valueOf(position.current_price_e8, 8))

    private fun convert(amounts: List<Pair<Currency, BigDecimal>>, settings: AppSettings,
        initialMissing: Set<MissingAmount> = emptySet()): ConvertedTotal {
        val base = settings.baseCurrency ?: return ConvertedTotal(BigDecimal.ZERO, null,
            initialMissing + MissingAmount(MissingKind.BASE_CURRENCY))
        val rates = settings.rates.filter { it.targetCurrency == base }.associateBy { it.sourceCurrency.code }
        val missing = initialMissing.toMutableSet()
        var result = BigDecimal.ZERO
        for ((currency, value) in amounts) {
            if (value.signum() == 0) continue
            val rate = if (currency == base) BigDecimal.ONE else rates[currency.code]?.rate
            if (rate == null) missing.add(MissingAmount(MissingKind.EXCHANGE_RATE, currency.code))
            else result = result.add(value.multiply(rate))
        }
        return ConvertedTotal(result, base, missing)
    }
}
