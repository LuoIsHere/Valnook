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
}
