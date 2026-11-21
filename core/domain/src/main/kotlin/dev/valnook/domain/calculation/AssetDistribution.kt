package dev.valnook.domain.calculation

import dev.valnook.domain.model.AssetSnapshot
import dev.valnook.domain.model.Currency
import java.math.BigDecimal

/**
 * One part of a distribution: a [label] and its value in the base currency. A currency part also has [native], its
 * amount in that currency (the [label] is its code).
 */
data class DistributionPart(val label: String, val value: BigDecimal, val native: BigDecimal? = null)

/**
 * How today's assets are spread, in the base [currency]: by account (in display order, negative totals included),
 * by asset type of the held positions and by currency (both sorted by label).
 */
data class AssetDistribution(
    val currency: Currency?,
    val accounts: List<DistributionPart>,
    val assetTypes: List<DistributionPart>,
    val currencies: List<DistributionPart>
) {
    companion object {
        fun calculate(snapshot: AssetSnapshot): AssetDistribution {
            val overview = AssetValuation.calculate(snapshot)
            // Same lookup as the totals; an amount without any rate is left out, as the totals leave it out.
            val rates = dev.valnook.domain.fx.FxRateResolver(snapshot.settings, snapshot.fx)
            fun convert(code: String, amount: BigDecimal): BigDecimal? = rates.today(code)?.let(amount::multiply)
            val held = snapshot.positions.filter { it.holding_quantity_e8 > 0 }
            val types = held.groupBy { it.type_name }.mapValues { (_, rows) ->
                rows.fold(BigDecimal.ZERO) { total, row ->
                    total + (convert(row.currency.code, AssetValuation.marketValue(row)) ?: BigDecimal.ZERO)
                }
            }
            val currencies = mutableMapOf<String, BigDecimal>()
            fun add(code: String, amount: BigDecimal) { currencies[code] = currencies.getOrDefault(code, BigDecimal.ZERO) + amount }
            snapshot.cash.forEach { add(it.currency.code, BigDecimal.valueOf(it.balance_minor, it.currency.fraction_digits)) }
            snapshot.deposits.filterNot { it.closed }
                .forEach { add(it.currency.code, BigDecimal.valueOf(it.principal_minor, it.currency.fraction_digits)) }
            held.forEach { add(it.currency.code, AssetValuation.marketValue(it)) }
            return AssetDistribution(snapshot.settings.baseCurrency,
                overview.accounts.map { DistributionPart(it.account.name, it.total.amount) },
                types.toSortedMap().map { (label, value) -> DistributionPart(label, value) },
                currencies.toSortedMap().mapNotNull { (code, amount) -> convert(code, amount)?.let { DistributionPart(code, it, amount) } })
        }
    }
}
