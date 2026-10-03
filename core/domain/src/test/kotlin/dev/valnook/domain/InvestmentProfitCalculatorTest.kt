package dev.valnook.domain

import dev.valnook.domain.calculation.InvestmentProfitCalculator as Calculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import java.math.BigDecimal
import java.math.RoundingMode
import org.junit.Test
import org.junit.Assert.*

class InvestmentProfitCalculatorTest {
    private fun e(value: String) = R.parse_e8(value)
    private val currency = Currency.of("USD")
    private fun asset(held: String, price: String = "180", remainingCost: String? = null) =
        Investment(1, 1, 1, "基金", "QQQ", "QQQ", currency, e(held), e(price), 0,
            remainingCost = remainingCost)
    private fun trade(id: Long, direction: Direction, quantity: String, price: String, time: Long = id,
        feeMinor: Long = 0) = Trade(id, 1, direction, e(quantity), e(price),
        R.amount(e(quantity), e(price), currency), currency, false, time, fee_minor = feeMinor)
    private fun decimal(expected: String, actual: BigDecimal?) = assertEquals(0, BigDecimal(expected).compareTo(requireNotNull(actual)))
    private fun displayed(expected: String, actual: BigDecimal?) = decimal(expected, actual?.setScale(2, RoundingMode.HALF_UP))

    @Test fun partial_sale_and_new_purchase_use_remaining_cost() {
        val history = listOf(trade(1, Direction.BUY, "10", "100"), trade(2, Direction.BUY, "10", "200"),
            trade(3, Direction.SELL, "5", "180"))
        val partial = Calculator.calculate(asset("15", price = "160"), history)
        decimal("2250", partial.remainingCost); decimal("150", partial.average_cost)
        decimal("150", partial.realized); decimal("150", partial.unrealized)
        val added = Calculator.calculate(asset("20", price = "160"), history + trade(4, Direction.BUY, "5", "120"))
        decimal("2850", added.remainingCost); decimal("142.5", added.average_cost)
        decimal("150", added.realized); decimal("350", added.unrealized)
    }
    @Test fun floating_percentage_uses_remaining_cost_and_has_no_fabricated_zero_denominator() {
        decimal("20", Calculator.calculate(asset("10", "120"),
            listOf(trade(1, Direction.BUY, "10", "100"))).unrealizedPercent)
        decimal("-10", Calculator.calculate(asset("10", "180"),
            listOf(trade(1, Direction.BUY, "10", "200"))).unrealizedPercent)
        val partial = Calculator.calculate(asset("5", price = "120"), listOf(
            trade(1, Direction.BUY, "10", "100"), trade(2, Direction.SELL, "5", "110")))
        decimal("500", partial.remainingCost)
        decimal("20", partial.unrealizedPercent)
        assertNull(Calculator.calculate(asset("10"), emptyList()).unrealizedPercent)
        assertNull(Calculator.calculate(asset("10"),
            listOf(trade(1, Direction.BUY, "10", "0"))).unrealizedPercent)
        assertNull(Calculator.calculate(asset("0"), emptyList()).unrealizedPercent)
    }
    @Test fun later_buy_does_not_reprice_prior_sale() {
        val result = Calculator.calculate(asset("15"), listOf(trade(1, Direction.BUY, "10", "100"),
            trade(2, Direction.SELL, "5", "120"), trade(3, Direction.BUY, "10", "200")))
        decimal("2500", result.remainingCost); decimal("100", result.realized); decimal("200", result.unrealized)
    }
    @Test fun liquidation_resets_current_cost_but_keeps_realized_history() {
        val history = listOf(trade(1, Direction.BUY, "1", "100"), trade(2, Direction.SELL, "1", "120"))
        val closed = Calculator.calculate(asset("0"), history)
        assertNull(closed.average_cost); decimal("0", closed.remainingCost); decimal("20", closed.realized)
        val reopened = Calculator.calculate(asset("1", price = "200"), history + trade(3, Direction.BUY, "1", "200"))
        decimal("200", reopened.average_cost); decimal("200", reopened.remainingCost)
        decimal("20", reopened.realized); decimal("0", reopened.unrealized)
    }
    @Test fun stable_time_order_and_correction_replay() {
        val buy = trade(2, Direction.BUY, "10", "100", 10)
        val sell = trade(3, Direction.SELL, "5", "120", 10)
        decimal("100", Calculator.calculate(asset("5"), listOf(sell, buy)).realized)
        decimal("150", Calculator.calculate(asset("5"), listOf(sell, buy.copy(execution_price_e8 = e("90"), amount_minor = 90000))).realized)
        decimal("0", Calculator.calculate(asset("10"), listOf(buy)).realized)
    }
    @Test fun sale_before_buy_is_an_explicit_history_conflict() {
        val missing = Calculator.calculate(asset("10"), emptyList())
        assertFalse(missing.cost_complete); assertNull(missing.realized); assertNull(missing.unrealized)
        val history = listOf(trade(1, Direction.SELL, "10", "120"), trade(2, Direction.BUY, "1", "200"))
        val reopened = Calculator.calculate(asset("1", price = "200"), history)
        assertFalse(reopened.cost_complete); assertFalse(reopened.realizedComplete); assertNull(reopened.realized)
        decimal("0", reopened.remainingCost); assertNull(reopened.unrealized)
    }
    @Test fun allocation_precision_is_not_reduced_to_currency_scale() {
        val result = Calculator.calculate(asset("0.2", price = "2"), listOf(
            trade(1, Direction.BUY, "0.3", "1.23"), trade(2, Direction.SELL, "0.1", "2")))
        displayed("0.08", result.realized); displayed("0.15", result.unrealized)
        assertTrue(result.remainingCost!!.scale() > 2)
        val closed = Calculator.calculate(asset("0"), listOf(trade(1, Direction.BUY, "0.3", "1.23"),
            trade(2, Direction.SELL, "0.1", "2"), trade(3, Direction.SELL, "0.2", "2")))
        decimal("0", closed.remainingCost); decimal("0.23", closed.realized)
    }
    @Test fun historical_sale_before_buy_conflict_is_explicit() {
        val history = listOf(trade(1, Direction.SELL, "5", "120"), trade(2, Direction.BUY, "10", "100"))
        assertFalse(Calculator.calculate(asset("5"), history).chronology_valid)
    }
    @Test fun exact_half_cent_display_is_rounded_only_at_boundary() {
        val result = Calculator.calculate(asset("2.9925"), listOf(trade(1, Direction.BUY, "3", "0.66666667"),
            trade(2, Direction.SELL, "0.0075", "2.66666667")))
        decimal("0.015", result.realized); displayed("0.02", result.realized)
    }
    @Test fun lifetime_turnover_can_exceed_long_while_each_record_remains_bounded() {
        val history = (1L..4L).map { id -> trade(id, if (id % 2 == 1L) Direction.BUY else Direction.SELL, "1000000000", "90000000") }
        val result = Calculator.calculate(asset("0"), history)
        assertNull(result.average_cost); decimal("0", result.realized); decimal("0", result.remainingCost)
    }
    @Test fun fees_raise_buy_cost_and_reduce_realized_sale_proceeds() {
        val result = Calculator.calculate(asset("5", price = "120"), listOf(
            trade(1, Direction.BUY, "10", "100", feeMinor = 200),
            trade(2, Direction.SELL, "5", "120", feeMinor = 300)))
        decimal("501", result.remainingCost)
        decimal("100.2", result.average_cost)
        decimal("96", result.realized)
        decimal("99", result.unrealized)
    }
}
