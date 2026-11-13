package dev.valnook.domain

import dev.valnook.domain.model.BillSettlement
import dev.valnook.domain.model.ReimburseState
import org.junit.Assert.assertEquals
import org.junit.Test

/** A reimbursable expense moves from pending through partly paid back to done; a waiver holds until taken back. */
class BillSettlementStateTest {
    private fun state(reimbursable: Boolean = true, waived: Boolean = false, paid: Long = 10_000, refunded: Long = 0,
        reimbursed: Long = 0) = BillSettlement.state(reimbursable, waived, paid, refunded, reimbursed)

    @Test fun not_to_be_paid_back_has_no_state() {
        assertEquals(ReimburseState.NONE, state(reimbursable = false, reimbursed = 0))
    }

    @Test fun pending_then_partly_then_done() {
        assertEquals(ReimburseState.PENDING, state())
        assertEquals(ReimburseState.PARTIAL, state(reimbursed = 1))
        assertEquals(ReimburseState.DONE, state(reimbursed = 10_000))
    }

    @Test fun a_refund_lowers_what_is_owed() {
        // 3 000 refunded leaves 7 000 to be paid back; once it is, nothing is left.
        assertEquals(ReimburseState.PARTIAL, state(refunded = 3_000, reimbursed = 6_999))
        assertEquals(ReimburseState.DONE, state(refunded = 3_000, reimbursed = 7_000))
        // Refunded in full, there is nothing to wait for.
        assertEquals(ReimburseState.DONE, state(refunded = 10_000))
    }

    @Test fun a_waiver_holds_whatever_came_back() {
        assertEquals(ReimburseState.WAIVED, state(waived = true))
        assertEquals(ReimburseState.WAIVED, state(waived = true, reimbursed = 4_000))
    }
}
