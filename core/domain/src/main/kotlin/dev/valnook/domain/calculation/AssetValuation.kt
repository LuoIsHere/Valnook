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
            InstrumentAssets(instrument, positions.filter { it.holding_quantity_e8 > 0 }
                .map(::marketValue).fold(BigDecimal.ZERO, BigDecimal::add),
                sumKnown(profits.map { it.unrealized }), sumKnown(profits.map { it.realized }))
        }
    }

    fun calculate(snapshot: AssetSnapshot): AssetOverview {
        val cashByAccount = snapshot.cash.groupBy { it.account_id }
        val depositsByAccount = snapshot.deposits.filterNot { it.closed }.groupBy { it.account_id }
        val positionsByAccount = snapshot.positions.groupBy { it.account_id }
        val rates = dev.valnook.domain.fx.FxRateResolver(snapshot.settings, snapshot.fx)
        val accounts = snapshot.accounts.map { account ->
            val balanceAccounts = cashByAccount[account.id].orEmpty()
            val savingsAmounts = balanceAccounts.filter { it.type == BalanceAccountType.SAVINGS }.map {
                it.currency to BigDecimal.valueOf(it.balance_minor, it.currency.fraction_digits)
            }
            val creditAmounts = balanceAccounts.filter { it.type == BalanceAccountType.CREDIT }.map {
                it.currency to BigDecimal.valueOf(it.balance_minor, it.currency.fraction_digits)
            }
            val deposits = depositsByAccount[account.id].orEmpty().map {
                it.currency to BigDecimal.valueOf(it.principal_minor, it.currency.fraction_digits)
            }
            val positions = positionsByAccount[account.id].orEmpty()
            val held = positions.filter { it.holding_quantity_e8 > 0 }
            val values = held.map { it.currency to marketValue(it) }
            val floating = held.map {
                val profit = InvestmentProfitCalculator.fromReadModel(it)
                it.currency to (profit.unrealized ?: BigDecimal.ZERO)
            }
            val floatingMissing = held.filter { it.remainingCost == null || !it.chronologyValid ||
                it.algorithmVersion != InvestmentProfitCalculator.ALGORITHM_VERSION }
                .map { MissingAmount(if (it.chronologyValid) MissingKind.CURRENT_COST else MissingKind.INVALID_HISTORY,
                    it.currency.code, it.id) }.toSet()
            val realized = positions.map { it.currency to (InvestmentProfitCalculator.fromReadModel(it).realized ?: BigDecimal.ZERO) }
            val realizedMissing = positions.filter { it.realizedProfit == null || !it.chronologyValid ||
                it.algorithmVersion != InvestmentProfitCalculator.ALGORITHM_VERSION }
                .map { MissingAmount(if (it.chronologyValid) MissingKind.HISTORICAL_COST else MissingKind.INVALID_HISTORY,
                    it.currency.code, it.id) }.toSet()
            AccountAssets(account, convert(savingsAmounts + creditAmounts + deposits + values, rates),
                convert(savingsAmounts, rates), convert(creditAmounts, rates), convert(deposits, rates),
                convert(values, rates),
                convert(floating, rates, floatingMissing), convert(realized, rates, realizedMissing),
                convert(balanceAccounts.filter { it.type == BalanceAccountType.SAVINGS && it.includeInAvailableCash }.map {
                    it.currency to BigDecimal.valueOf(it.balance_minor, it.currency.fraction_digits)
                }, rates))
        }
        fun total(select: (AccountAssets) -> ConvertedTotal) = sum(accounts.map(select), snapshot.settings.baseCurrency)
        return AssetOverview(accounts, total { it.total }, total { it.cash }, total { it.creditBalance }, total { it.depositValue }, total { it.investmentValue },
            total { it.floating }, total { it.realized }, total { it.availableCash })
    }

    /** Adds totals already converted to [base], keeping every missing amount. */
    fun sum(totals: List<ConvertedTotal>, base: Currency?): ConvertedTotal = ConvertedTotal(
        totals.fold(BigDecimal.ZERO) { result, total -> result.add(total.amount) }, base,
        totals.flatMap { it.missing }.toSet() +
            if (base == null) setOf(MissingAmount(MissingKind.BASE_CURRENCY)) else emptySet())

    fun marketValue(position: Investment): BigDecimal = BigDecimal.valueOf(position.holding_quantity_e8, 8)
        .multiply(BigDecimal.valueOf(position.current_price_e8, 8))

    private fun convert(amounts: List<Pair<Currency, BigDecimal>>, rates: dev.valnook.domain.fx.FxRateResolver,
        initialMissing: Set<MissingAmount> = emptySet()): ConvertedTotal {
        val base = rates.base ?: return ConvertedTotal(BigDecimal.ZERO, null,
            initialMissing + MissingAmount(MissingKind.BASE_CURRENCY))
        var result = BigDecimal.ZERO
        val missing = initialMissing.toMutableSet()
        for ((currency, value) in amounts) {
            if (value.signum() == 0) continue
            val rate = rates.today(currency.code)
            // Without any rate the amount is left out rather than guessed.
            if (rate == null) missing += MissingAmount(MissingKind.EXCHANGE_RATE, currency.code)
            else result = result.add(value.multiply(rate))
        }
        return ConvertedTotal(result, base, missing)
    }
}
