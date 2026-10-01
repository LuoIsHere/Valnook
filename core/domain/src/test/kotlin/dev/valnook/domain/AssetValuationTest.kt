package dev.valnook.domain

import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class AssetValuationTest {
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private fun position(account: Long, quantity: Long = 1000000000, cost: String? = "1000", realized: String? = "100") =
        Investment(account, account, 1, "ETF", "QQQ", "QQQ", usd, 0, quantity, 10000000000, 0,
            remainingCost = cost, realizedProfit = realized)
    private fun snapshot(rate: String = "7.2") = AssetSnapshot(
        listOf(SavingsAccount(1, "A", ""), SavingsAccount(2, "现金账户", "")),
        listOf(CashBalance(1, cny, 100000, 1), CashBalance(1, usd, 10000, 1), CashBalance(2, cny, 50000, 1)),
        listOf(TermDeposit(1, 1, usd, 100000, 0, 1, 366, 1000, false, false, null)),
        listOf(position(1)), emptyList(), AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal(rate))), 1))
    private fun decimal(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test fun all_account_top_assets_include_cash_only_account() {
        val result = AssetValuation.calculate(snapshot())
        decimal("16620", result.total.amount); decimal("2220", result.cash.amount)
        decimal("16120", result.accounts.first().total.amount); decimal("1720", result.accounts.first().cash.amount)
        decimal("7200", result.investmentValue.amount)
    }
    @Test fun deposit_maturity_does_not_add_interest_until_selected_cash_settlement() {
        val initial = snapshot()
        val noLink = AssetValuation.calculate(initial.copy(deposits = emptyList()))
        decimal("8920", noLink.accounts.first().total.amount)
        val returned = initial.cash.map { if (it.account_id == 1L && it.currency == usd) it.copy(balance_minor = 111000) else it }
        decimal("16192", AssetValuation.calculate(initial.copy(deposits = emptyList(), cash = returned)).accounts.first().total.amount)
    }
    @Test fun current_fx_revalues_historical_profit_without_mutating_source() {
        decimal("720", AssetValuation.calculate(snapshot()).realized.amount)
        decimal("700", AssetValuation.calculate(snapshot("7.0")).realized.amount)
        assertEquals("100", snapshot().positions.first().realizedProfit)
    }
    @Test fun missing_base_and_cost_stay_distinct_with_default_exchange_rates() {
        val base = AssetValuation.calculate(snapshot().copy(settings = AppSettings()))
        assertNull(base.total.currency); assertTrue(base.total.missing.any { it.kind == MissingKind.BASE_CURRENCY })
        val pair = AssetValuation.calculate(snapshot().copy(settings = AppSettings(cny)))
        assertTrue(pair.total.complete); decimal("3600", pair.total.amount)
        val cost = AssetValuation.calculate(snapshot().copy(positions = listOf(position(1, cost = null))))
        assertTrue(cost.total.complete); assertFalse(cost.floating.complete); assertTrue(cost.realized.complete)
        val switched = AssetValuation.calculate(snapshot().copy(settings = snapshot().settings.copy(baseCurrency = Currency.of("HKD"))))
        assertTrue(switched.total.complete); decimal("3600", switched.total.amount)
    }
    @Test fun default_one_applies_to_cash_deposits_market_and_profits_until_a_pair_is_set() {
        val input = snapshot().copy(settings = AppSettings(cny), positions = listOf(position(1, cost = "800")))
        val result = AssetValuation.calculate(input)
        assertTrue(result.total.complete); assertTrue(result.floating.complete); assertTrue(result.realized.complete)
        decimal("3600", result.total.amount); decimal("1600", result.cash.amount)
        decimal("1000", result.investmentValue.amount); decimal("200", result.floating.amount); decimal("100", result.realized.amount)
        val configured = AssetValuation.calculate(input.copy(settings = snapshot().settings))
        decimal("16620", configured.total.amount); decimal("1440", configured.floating.amount); decimal("720", configured.realized.amount)
        assertEquals("800", input.positions.single().remainingCost)
    }
    @Test fun zero_values_need_no_fx_and_closed_history_stays_in_totals() {
        val initial = snapshot().copy(positions = listOf(position(1, 0, "0")))
        val result = AssetValuation.calculate(initial)
        decimal("0", result.investmentValue.amount); decimal("0", result.floating.amount); decimal("720", result.realized.amount)
        val zero = initial.copy(cash = emptyList(), deposits = emptyList(), positions = listOf(position(1, 0, "0", "0")), settings = AppSettings(cny))
        assertTrue(AssetValuation.calculate(zero).total.complete)
    }
    @Test fun independent_account_costs_produce_shared_instrument_totals() {
        val a = position(1, 500000000, "500").copy(current_price_e8 = 18000000000)
        val b = position(2, 1000000000, "2000", "0").copy(current_price_e8 = 18000000000)
        decimal("400", InvestmentProfitCalculator.fromReadModel(a).unrealized!!)
        decimal("-200", InvestmentProfitCalculator.fromReadModel(b).unrealized!!)
        val result = AssetValuation.calculate(snapshot().copy(positions = listOf(a, b)))
        decimal("1440", result.floating.amount); decimal("720", result.realized.amount)
    }
    @Test fun exact_values_are_summed_before_display_rounding() {
        val tiny = position(1, 1000000, "0", "0").copy(current_price_e8 = 40000000)
        val second = tiny.copy(id = 2, account_id = 2)
        decimal("0.008", AssetValuation.calculate(snapshot().copy(positions = listOf(tiny, second),
            settings = AppSettings(usd))).investmentValue.amount)
    }
    @Test fun outdated_cost_algorithm_is_incomplete_and_not_presented_as_valid_profit() {
        val initial = snapshot().copy(positions = listOf(position(1).copy(algorithmVersion = 1)))
        val result = AssetValuation.calculate(initial)
        assertTrue(result.total.complete)
        assertFalse(result.realized.complete)
        assertFalse(result.floating.complete)
        decimal("0", result.realized.amount)
        assertNull(InvestmentProfitCalculator.fromReadModel(initial.positions.single()).average_cost)
    }
    @Test fun investment_total_uses_current_market_value_from_all_accounts() {
        val first = position(1, 1000000000, "800", "0")
        val second = position(2, 1500000000, "3000", "0")
        val result = AssetValuation.calculate(snapshot().copy(positions = listOf(first, second)))
        decimal("18000", result.investmentValue.amount)
        assertTrue(result.investmentValue.complete)
    }
    @Test fun current_market_totals_exclude_realized_profit_and_closed_positions() {
        val positions = listOf(position(1, 100000000, "80", "20"),
            position(1, 0, "0", "500").copy(id = 3), position(2, 0, "0", "700"))
            .map { it.copy(instrumentId = 7) }
        val input = snapshot().copy(cash = emptyList(), deposits = emptyList(), positions = positions,
            instruments = listOf(Instrument(7, "QQQ", "QQQ", 1, "ETF", usd, 10000000, true, 1, 0)))
        val result = AssetValuation.calculate(input)
        decimal("720", result.investmentValue.amount); decimal("720", result.total.amount)
        decimal("720", result.accounts.first().investmentValue.amount)
        decimal("0", result.accounts.last().investmentValue.amount)
        decimal("8784", result.realized.amount)
        val summary = AssetValuation.instrumentSummaries(input).single()
        decimal("100", summary.marketValue); decimal("1220", summary.realized!!)
        val cleared = input.copy(positions = positions.map { it.copy(holding_quantity_e8 = 0, remainingCost = "0") })
        decimal("0", AssetValuation.calculate(cleared).investmentValue.amount)
        decimal("8784", AssetValuation.calculate(cleared).realized.amount)
    }
    @Test fun shared_instrument_totals_include_closed_history_and_unused_catalog_items() {
        val instrument = Instrument(7, "QQQ", "QQQ", 1, "ETF", usd, 18000000, true, 1, 0)
        val a = position(1, 500000000, "500", "100").copy(instrumentId = 7, current_price_e8 = 18000000000)
        val b = position(2, 1000000000, "2000", "0").copy(instrumentId = 7, current_price_e8 = 18000000000)
        val closed = position(3, 0, "0", "300").copy(instrumentId = 7)
        val input = snapshot().copy(positions = listOf(a, b, closed),
            instruments = listOf(instrument, instrument.copy(id = 8, name = "unused", currencyLocked = false)))
        val totals = AssetValuation.instrumentSummaries(input)
        decimal("2700", totals[0].marketValue)
        decimal("200", totals[0].floating!!)
        decimal("400", totals[0].realized!!)
        decimal("0", totals[1].marketValue)
        decimal("0", totals[1].realized!!)
    }
}
