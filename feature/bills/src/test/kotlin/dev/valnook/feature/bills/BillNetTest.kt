package dev.valnook.feature.bills

import dev.valnook.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

/** An expense counts in the totals for what it still costs after its refunds and reimbursements. */
class BillNetTest {
    private fun expense(amount: Long, discount: Long = 0, refunded: Long = 0, reimbursed: Long = 0) = Bill(
        id = 1, type = BillType.EXPENSE, occurredAtMs = 0, amountMinor = amount, currency = Currency.of("CNY"),
        discountMinor = discount, cashAccountId = 1, accountCurrency = Currency.of("CNY"), accountAmountMinor = amount - discount,
        rate = BigDecimal.ONE, rateSource = BillRateSource.SAME, transferKind = null, toCashAccountId = null, toCurrency = null,
        toAmountMinor = null, feeMinor = 0, counterparty = "", categoryId = 1, subCategoryId = null, countInStats = true,
        reimbursable = reimbursed > 0, reimburseState = ReimburseState.NONE, parentId = null, note = "", revision = 1,
        createdAtMs = 0, updatedAtMs = 0, refundedMinor = refunded, reimbursedMinor = reimbursed)

    private fun net(bill: Bill, value: String) = BillsViewModel.net(bill, BigDecimal(value)).setScale(2, java.math.RoundingMode.HALF_UP)

    @Test fun nothing_back_keeps_the_whole_amount() {
        assertEquals(BigDecimal("90.00"), net(expense(10_000, discount = 1_000), "90"))
    }

    @Test fun refunds_and_reimbursements_take_their_share_off() {
        // 30 of 90 refunded and 20 paid back: 40 of 90 is still spent, in whatever currency the value is.
        assertEquals(BigDecimal("40.00"), net(expense(10_000, discount = 1_000, refunded = 3_000, reimbursed = 2_000), "90"))
        assertEquals(BigDecimal("6.32"), net(expense(10_000, discount = 1_000, refunded = 3_000, reimbursed = 2_000), "14.22"))
    }

    @Test fun all_back_costs_nothing() {
        assertEquals(BigDecimal("0.00"), net(expense(5_000, refunded = 5_000), "50"))
    }

    @Test fun what_was_not_paid_back_counts_once_paying_back_is_given_up() {
        val rates = dev.valnook.domain.fx.FxRateResolver(AppSettings(baseCurrency = Currency.of("CNY")), dev.valnook.domain.fx.FxContext())
        // 20 to be paid back and kept out of income and expense; 5 came back.
        val owed = expense(2_000, reimbursed = 500).copy(countInStats = false, reimburseState = ReimburseState.PARTIAL)
        assertEquals(BigDecimal("0.00"), BillsViewModel.totals(listOf(owed), rates).expense)
        assertEquals(false, BillFilter(counted = true).matches(owed))
        // The other 15 given up: that is the user's own spending now.
        val waived = owed.copy(reimburseState = ReimburseState.WAIVED)
        assertEquals(BigDecimal("15.00"), BillsViewModel.totals(listOf(waived), rates).expense)
        assertEquals(true, BillFilter(counted = true).matches(waived))
        // Given up with nothing paid back: all of it.
        val none = expense(2_000).copy(countInStats = false, reimbursable = true, reimburseState = ReimburseState.WAIVED)
        assertEquals(BigDecimal("20.00"), BillsViewModel.totals(listOf(none), rates).expense)
    }

    @Test fun what_is_owed_shows_in_the_base_currency_or_else_in_each_currency() {
        val cny = Currency.of("CNY")
        val usd = Currency.of("USD")
        assertEquals("428.00 CNY", pendingText(ReimbursementSummary(3, BigDecimal("428.00"), cny, partial = false)))
        // A currency without a rate leaves the sum incomplete, so each currency shows what is left in it.
        assertEquals("780.00 CNY + 20.00 USD", pendingText(ReimbursementSummary(2, BigDecimal("780.00"), cny, partial = true,
            byCurrency = listOf(cny to 78_000L, usd to 2_000L))))
    }
}
