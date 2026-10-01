package dev.valnook.domain.calculation

import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Cumulative purchase average: sells never remove purchases from the cost denominator. */
object InvestmentProfitCalculator {
    fun calculate(asset: Investment, trades: List<Trade>): InvestmentProfit {
        val scale=asset.currency.fraction_digits
        fun units(value:Long)=BigDecimal.valueOf(value,8)
        fun money(value:BigDecimal)=value.setScale(scale,RoundingMode.HALF_UP)
        val complete=asset.opening_quantity_e8==0L || asset.opening_cost_price_e8!=null
        if(!complete)return InvestmentProfit(null,null,null,false,true)
        var purchased=units(asset.opening_quantity_e8)
        var cost=money(purchased.multiply(units(asset.opening_cost_price_e8 ?: 0)))
        var held=purchased
        var realized=BigDecimal.ZERO
        var chronological=true
        fun average():BigDecimal?=if(purchased.signum()==0)null else cost.divide(purchased,32,RoundingMode.HALF_UP)
        // Replaying dated records makes historical corrections deterministic; later buys cannot
        // change an earlier sale's average. ID breaks ties for records at exactly the same time.
        for(trade in trades.sortedWith(compareBy<Trade>{it.occurred_at_ms}.thenBy{it.id})) {
            require(trade.investment_id==asset.id && trade.currency==asset.currency)
            val quantity=units(trade.quantity_e8)
            if(trade.direction==Direction.BUY) {
                purchased=purchased.add(quantity)
                cost=cost.add(BigDecimal.valueOf(trade.amount_minor,scale))
                held=held.add(quantity)
            } else {
                held=held.subtract(quantity)
                val price=average()
                if(held.signum()<0 || price==null)chronological=false
                // Divide the exact profit numerator only at the currency boundary. Multiplying
                // a rounded repeating average could turn a half-cent gain into the wrong cent.
                if(price!=null)realized=realized.add(
                    BigDecimal.valueOf(trade.amount_minor,scale).multiply(purchased).subtract(quantity.multiply(cost))
                        .divide(purchased,scale,RoundingMode.HALF_UP))
            }
        }
        if(held.compareTo(units(asset.holding_quantity_e8))!=0)chronological=false
        val average=average()
        val unrealized=when {
            asset.holding_quantity_e8==0L->money(BigDecimal.ZERO)
            average!=null->units(asset.holding_quantity_e8).multiply(units(asset.current_price_e8).multiply(purchased).subtract(cost))
                .divide(purchased,scale,RoundingMode.HALF_UP)
            else->null
        }
        return InvestmentProfit(average,if(chronological)money(realized) else null,unrealized,true,chronological)
    }
}
