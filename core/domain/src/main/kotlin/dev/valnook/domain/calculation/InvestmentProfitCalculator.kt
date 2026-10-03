package dev.valnook.domain.calculation

import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.math.RoundingMode

/** Independent moving-average cost chain for one account position. Never replay cash here. */
object InvestmentProfitCalculator {
    const val ALGORITHM_VERSION = 4
    const val ALLOCATION_SCALE = 32

    fun calculate(asset: Investment, trades: List<Trade>): InvestmentProfit {
        var quantityE8 = 0L
        var remainingCost: BigDecimal? = BigDecimal.ZERO
        var realized: BigDecimal? = BigDecimal.ZERO
        var conflictTradeId: Long? = null
        var chronological = true
        for (trade in trades.sortedWith(compareBy<Trade> { it.occurred_at_ms }.thenBy { it.id })) {
            require(trade.investment_id == asset.id && trade.currency == asset.currency)
            val amount = BigDecimal.valueOf(trade.amount_minor, asset.currency.fraction_digits)
            val fee = BigDecimal.valueOf(trade.fee_minor, asset.currency.fraction_digits)
            if (trade.direction == Direction.BUY) {
                quantityE8 = DecimalRules.add(quantityE8, trade.quantity_e8)
                remainingCost = remainingCost?.add(amount)?.add(fee)
            } else {
                if (trade.quantity_e8 > quantityE8 || quantityE8 <= 0) {
                    chronological = false
                    conflictTradeId = conflictTradeId ?: trade.id
                    break
                }
                // Full liquidation consumes the exact residual, eliminating allocation dust.
                val allocated = if (trade.quantity_e8 == quantityE8) remainingCost else
                    remainingCost?.multiply(BigDecimal.valueOf(trade.quantity_e8))
                        ?.divide(BigDecimal.valueOf(quantityE8), ALLOCATION_SCALE, RoundingMode.HALF_UP)
                realized = if (allocated == null || realized == null) null else
                    realized.add(amount.subtract(fee).subtract(allocated))
                quantityE8 -= trade.quantity_e8
                remainingCost = if (quantityE8 == 0L) BigDecimal.ZERO else
                    if (remainingCost == null) null else remainingCost.subtract(requireNotNull(allocated))
            }
        }
        if (quantityE8 != asset.holding_quantity_e8) chronological = false
        return value(quantityE8, asset.current_price_e8, remainingCost, realized, chronological, conflictTradeId)
    }

    fun fromReadModel(asset: Investment): InvestmentProfit = value(
        asset.holding_quantity_e8, asset.current_price_e8,
        asset.remainingCost?.toBigDecimal(), asset.realizedProfit?.toBigDecimal(),
        asset.chronologyValid && asset.algorithmVersion == ALGORITHM_VERSION, null
    )

    private fun value(quantityE8: Long, priceE8: Long, cost: BigDecimal?, realized: BigDecimal?,
        valid: Boolean, conflictId: Long?): InvestmentProfit {
        val quantity = BigDecimal.valueOf(quantityE8, 8)
        val average = if (quantityE8 > 0 && valid) cost?.divide(quantity, ALLOCATION_SCALE, RoundingMode.HALF_UP) else null
        val floating = if (valid) cost?.let { quantity.multiply(BigDecimal.valueOf(priceE8, 8)).subtract(it) } else null
        val percentage = if (quantityE8 > 0 && floating != null && cost != null && cost.signum() > 0)
            floating.multiply(BigDecimal("100")).divide(cost, ALLOCATION_SCALE, RoundingMode.HALF_UP) else null
        return InvestmentProfit(average, if (valid) realized else null, floating, cost != null && valid,
            valid, cost, realized != null && valid, conflictId, percentage)
    }
}
