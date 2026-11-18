package dev.valnook.domain

import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/** Income and expense count each bill at its own day's rate, net of what came back, and only when it counts. */
class BillCountingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val jpy = Currency.of("JPY")
    private val day1 = LocalDate.parse("2026-11-01")
    private val day2 = LocalDate.parse("2026-11-02")
    private val day3 = LocalDate.parse("2026-11-03")

    /** USD has a current rate of 7.0; on day 1 and 2 stored days of 7.2 and 7.3. JPY has no rate at all. */
    private val rates = BillDayRates(
        FxRateResolver(AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.0")))), FxContext()),
        mapOf("USD" to mapOf(day1 to BigDecimal("7.2"), day2 to BigDecimal("7.3"))))

    private var nextId = 1L
    private fun bill(type: BillType, amount: Long, on: LocalDate, currency: Currency = cny, discount: Long = 0,
        counted: Boolean = true) = Bill(
        id = nextId++, type = type, occurredAtMs = on.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
        amountMinor = amount, currency = currency, discountMinor = discount, cashAccountId = 1, accountCurrency = currency,
        accountAmountMinor = amount - discount, rate = BigDecimal.ONE, rateSource = BillRateSource.SAME,
        transferKind = if (type == BillType.TRANSFER) TransferKind.INTERNAL else null, toCashAccountId = null,
        toCurrency = null, toAmountMinor = null, feeMinor = 0, counterparty = "", categoryId = 1, subCategoryId = null,
        countInStats = counted, reimbursable = false, reimburseState = ReimburseState.NONE, parentId = null, note = "",
        revision = 1, createdAtMs = 0, updatedAtMs = 0)

    private fun counting(vararg bills: Bill) = BillCounting(bills.toList(), rates, zone)
    private fun decimal(expected: String, actual: BigDecimal?) = assertEquals(BigDecimal(expected), actual)

    @Test fun each_bill_uses_its_own_days_rate_then_todays() {
        val totals = counting(bill(BillType.EXPENSE, 1_000, day1, usd), bill(BillType.EXPENSE, 1_000, day2, usd),
            bill(BillType.EXPENSE, 1_000, day3, usd)).totals()
        // 72 on day 1, 73 on day 2, and day 3 has no stored day so today's 70.
        decimal("215.00", totals.expense)
        assertFalse(totals.partial)
        assertTrue(totals.converted)
        assertEquals(BillDayRate(BigDecimal("7.3"), day2), rates.on("USD", day2))
        assertEquals(BillDayRate(BigDecimal("7.0"), null), rates.on("USD", day3))
    }

    @Test fun no_rate_leaves_the_bill_out_and_marks_the_total() {
        val totals = counting(bill(BillType.EXPENSE, 500, day1, jpy), bill(BillType.EXPENSE, 2_000, day1)).totals()
        decimal("20.00", totals.expense)
        assertTrue(totals.partial)
        assertNull(rates.on("JPY", day1))
    }

    @Test fun the_base_currency_needs_no_rate() {
        val totals = counting(bill(BillType.EXPENSE, 1_234, day3), bill(BillType.INCOME, 50_000, day3)).totals()
        decimal("12.34", totals.expense)
        decimal("500.00", totals.income)
        decimal("487.66", totals.balance)
        assertFalse(totals.converted)
        assertFalse(totals.partial)
        // Without a base currency nothing can be counted.
        val none = BillCounting(listOf(bill(BillType.EXPENSE, 100, day1)),
            BillDayRates(FxRateResolver(AppSettings(), FxContext())), zone).totals()
        assertTrue(none.partial)
    }

    @Test fun refunds_come_off_the_expense_and_count_for_nothing_themselves() {
        // 100 less 10 off; 30 refunded and 20 paid back, whenever they arrived: 40 is still spent, at day 1's rate.
        val dinner = bill(BillType.EXPENSE, 10_000, day1, usd, discount = 1_000)
            .copy(refundedMinor = 3_000, reimbursedMinor = 2_000)
        val refund = bill(BillType.REFUND, 3_000, day3, usd).copy(parentId = dinner.id)
        val back = bill(BillType.REIMBURSEMENT, 2_000, day3, usd).copy(parentId = dinner.id)
        val moved = bill(BillType.TRANSFER, 9_999, day3)
        val totals = counting(dinner, refund, back, moved).totals()
        decimal("288.00", totals.expense)
        decimal("0.00", totals.income)
        assertFalse(counting().counts(refund))
        assertFalse(counting().counts(moved))
    }

    @Test fun bills_left_out_still_have_a_value_to_show() {
        val left = bill(BillType.EXPENSE, 4_000, day2, usd, counted = false)
        val counting = counting(left)
        decimal("0.00", counting.totals().expense)
        assertFalse(counting.counts(left))
        assertEquals(0, BigDecimal("292").compareTo(counting.valueOf(left)))
    }

    @Test fun paying_back_given_up_counts_what_was_not_paid_back() {
        val owed = bill(BillType.EXPENSE, 2_000, day1, counted = false).copy(reimbursable = true,
            reimburseState = ReimburseState.PARTIAL, reimbursedMinor = 500)
        decimal("0.00", counting(owed).totals().expense)
        decimal("15.00", counting(owed.copy(reimburseState = ReimburseState.WAIVED)).totals().expense)
    }

    @Test fun days_follow_the_zone() {
        // 23:30 on day 1 in Shanghai is still day 1 there, though day 1 15:30 in UTC.
        val late = bill(BillType.EXPENSE, 100, day1).copy(
            occurredAtMs = day1.atTime(23, 30).atZone(zone).toInstant().toEpochMilli())
        assertEquals(day1, counting(late).dateOf(late))
    }
}
