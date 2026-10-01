package dev.valnook.domain

import dev.valnook.domain.calculation.InvestmentProfitCalculator as Calculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import java.math.BigDecimal
import org.junit.Test
import org.junit.Assert.*

class InvestmentProfitCalculatorTest {
    private fun e(value:String)=R.parse_e8(value)
    private val currency=Currency.of("USD")
    private fun asset(held:String,opening:String="0",cost:String?=null,price:String="180")=
        Investment(1,1,1,"基金","QQQ","QQQ",currency,e(opening),e(held),e(price),0,cost?.let{e(it)})
    private fun trade(id:Long,direction:Direction,quantity:String,price:String,time:Long=id)=
        Trade(id,1,direction,e(quantity),e(price),R.amount(e(quantity),e(price),currency),currency,false,time)
    private fun decimal(expected:String,actual:BigDecimal?)=assertEquals(0,BigDecimal(expected).compareTo(requireNotNull(actual)))

    @Test fun weighted_purchases_and_sales_leave_cost_unchanged() {
        val buys=listOf(trade(1,Direction.BUY,"10","100"),trade(2,Direction.BUY,"20","160"))
        val before=Calculator.calculate(asset("30"),buys)
        val after=Calculator.calculate(asset("25"),buys+trade(3,Direction.SELL,"5","180"))
        decimal("140",before.average_cost);decimal("140",after.average_cost)
        decimal("200.00",after.realized);decimal("1000.00",after.unrealized)
    }
    @Test fun later_purchases_do_not_reprice_previous_realized_profit() {
        val trades=listOf(trade(1,Direction.BUY,"10","100"),trade(2,Direction.SELL,"5","120"),trade(3,Direction.BUY,"10","200"))
        val summary=Calculator.calculate(asset("15"),trades)
        decimal("150",summary.average_cost);decimal("100",summary.realized);decimal("450",summary.unrealized)
    }
    @Test fun closed_position_keeps_lifetime_purchase_average_when_reopened() {
        val trades=listOf(trade(1,Direction.BUY,"10","100"),trade(2,Direction.SELL,"10","120"))
        val closed=Calculator.calculate(asset("0"),trades)
        decimal("100",closed.average_cost);decimal("200",closed.realized);decimal("0",closed.unrealized)
        val reopened=Calculator.calculate(asset("10"),trades+trade(3,Direction.BUY,"10","200"))
        decimal("150",reopened.average_cost);decimal("200",reopened.realized)
    }
    @Test fun dated_replay_and_historical_correction_recompute_affected_sales() {
        val buy=trade(2,Direction.BUY,"10","100",10)
        val sell=trade(1,Direction.SELL,"5","120",20)
        decimal("100",Calculator.calculate(asset("5"),listOf(sell,buy)).realized)
        decimal("150",Calculator.calculate(asset("5"),listOf(sell,buy.copy(execution_price_e8=e("90"),amount_minor=90000))).realized)
        decimal("0",Calculator.calculate(asset("10"),listOf(buy)).realized)
    }
    @Test fun opening_cost_is_separate_from_current_market_price() {
        val missing=Calculator.calculate(asset("10","10"),emptyList())
        assertFalse(missing.cost_complete);assertNull(missing.realized);assertNull(missing.unrealized)
        val summary=Calculator.calculate(asset("8","10","100"),listOf(trade(1,Direction.SELL,"2","130")))
        decimal("100",summary.average_cost);decimal("60",summary.realized);decimal("640",summary.unrealized)
    }
    @Test fun fractional_cost_uses_booked_amounts_and_currency_rounding() {
        val summary=Calculator.calculate(asset("0.2",price="2"),listOf(
            trade(1,Direction.BUY,"0.3","1.23"),trade(2,Direction.SELL,"0.1","2")))
        decimal("0.08",summary.realized);decimal("0.15",summary.unrealized)
        assertTrue(summary.chronology_valid)
    }
    @Test fun backdated_sale_before_available_shares_is_explicitly_unresolved() {
        val summary=Calculator.calculate(asset("5"),listOf(trade(1,Direction.SELL,"5","120"),trade(2,Direction.BUY,"10","100")))
        assertFalse(summary.chronology_valid);assertNull(summary.realized)
    }
    @Test fun repeating_average_does_not_corrupt_exact_half_cent_rounding() {
        val summary=Calculator.calculate(asset("2.9925"),listOf(
            trade(1,Direction.BUY,"3","0.66666667"),trade(2,Direction.SELL,"0.0075","2.66666667")))
        decimal("0.02",summary.realized)
    }
    @Test fun lifetime_turnover_larger_than_long_does_not_overflow() {
        val trades=(1L..4L).map { id->trade(id,if(id%2L==1L)Direction.BUY else Direction.SELL,
            "1000000000","90000000") }
        val summary=Calculator.calculate(asset("0"),trades)
        decimal("90000000",summary.average_cost)
        decimal("0",summary.realized)
    }
}
