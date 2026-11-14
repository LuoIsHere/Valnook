package dev.valnook.feature.bills

import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/** A form filled in elsewhere saves exactly as the bill page would, or is left for the page when it could not. */
class BillPrefillTest {
    private fun account(id: Long, currency: String) = BillAccountChoice(id, 1, "Synthetic Bank", "S$id", Currency.of(currency),
        0, false, null, false, 0, true, 0, id, "bank", null)
    private val accounts = listOf(account(1, "CNY"), account(2, "CNY"), account(3, "USD"))
    private val settings = AppSettings(Currency.of("CNY"), listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2"))))
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = LocalDateTime.of(2026, 11, 14, 8, 30, 15)

    private fun draft(form: BillForm) = BillPrefill.draft(form, accounts, settings, FxContext(), emptyMap(), zone)

    @Test fun an_expense_to_be_paid_back_is_kept_out_of_the_totals_like_on_the_page() {
        val form = BillPrefill.form(BillType.EXPENSE, 6800, "CNY", time, accounts, "CNY", discountMinor = 300,
            cashAccountId = 1, categoryId = 10, subCategoryId = 11, note = "Lunch", counterparty = "Team", reimbursable = true)
        assertNull(form.currencyCode)
        assertEquals(LocalDateTime.of(2026, 11, 14, 8, 30), form.occurredAt)
        val bill = draft(form)!!
        assertEquals(BillType.EXPENSE, bill.type)
        assertEquals(6800L to 300L, bill.amountMinor to bill.discountMinor)
        assertEquals(6500L, bill.accountAmountMinor)
        assertEquals(10L to 11L, bill.categoryId to bill.subCategoryId)
        assertTrue(bill.reimbursable)
        assertFalse(bill.countInStats)
        assertEquals("Team", bill.counterparty)
        assertEquals(time.withSecond(0).atZone(zone).toInstant().toEpochMilli(), bill.occurredAtMs)
        // Unmarked on the page it counts again, as a bill marked there would.
        assertEquals(true, form.countedBeforeReimbursable)
    }

    @Test fun another_currency_is_converted_at_the_suggested_rate() {
        val form = BillPrefill.form(BillType.EXPENSE, 1000, "USD", time, accounts, "CNY", cashAccountId = 1, categoryId = 10)
        assertEquals("USD", form.currencyCode)
        val bill = draft(form)!!
        assertEquals("USD", bill.currencyCode)
        assertEquals(7200L, bill.accountAmountMinor)
        // Without a rate the page has to ask for one.
        assertNull(BillPrefill.draft(form, accounts, AppSettings(Currency.of("CNY")), FxContext(), emptyMap(), zone))
    }

    @Test fun incomplete_forms_are_left_for_the_page() {
        assertNull(draft(BillPrefill.form(BillType.EXPENSE, null, "CNY", time, accounts, "CNY", categoryId = 10)))
        assertNull(draft(BillPrefill.form(BillType.EXPENSE, 100, "CNY", time, accounts, "CNY")))
        assertNull(draft(BillPrefill.form(BillType.TRANSFER, 100, "CNY", time, accounts, "CNY")))
        // An account no longer there is not kept: the bill goes on no account, as the page would show it.
        val gone = BillPrefill.form(BillType.INCOME, 100, "CNY", time, accounts, "CNY", cashAccountId = 99, categoryId = 20)
        assertNull(gone.cashAccountId)
        assertEquals(BillType.INCOME, draft(gone)!!.type)
    }

    @Test fun transfers_inside_outside_and_between_currencies() {
        val inside = draft(BillPrefill.form(BillType.TRANSFER, 500, "CNY", time, accounts, "CNY", cashAccountId = 1, toCashAccountId = 2))!!
        assertEquals(TransferKind.INTERNAL, inside.transferKind)
        assertEquals(500L, inside.toAmountMinor)
        val out = draft(BillPrefill.form(BillType.TRANSFER, 500, "CNY", time, accounts, "CNY", cashAccountId = 1, counterparty = "Landlord"))!!
        assertEquals(TransferKind.TO_EXTERNAL, out.transferKind)
        assertEquals("Landlord", out.counterparty)
        val into = draft(BillPrefill.form(BillType.TRANSFER, 500, "CNY", time, accounts, "CNY", toCashAccountId = 3))!!
        assertEquals(TransferKind.FROM_EXTERNAL, into.transferKind)
        assertEquals("USD", into.currencyCode)
        val cross = draft(BillPrefill.form(BillType.TRANSFER, 7200, "CNY", time, accounts, "CNY", cashAccountId = 1, toCashAccountId = 3))!!
        assertEquals(1000L, cross.toAmountMinor)
        // Said by what arrived: what left follows at the same rate.
        val received = draft(BillPrefill.form(BillType.TRANSFER, null, "CNY", time, accounts, "CNY", cashAccountId = 1,
            toCashAccountId = 3, toAmountMinor = 1000))!!
        assertEquals(7200L to 1000L, received.amountMinor to received.toAmountMinor)
        // Without a rate both sides cannot be known: the page asks.
        assertNull(BillPrefill.draft(BillPrefill.form(BillType.TRANSFER, null, "CNY", time, accounts, "CNY", cashAccountId = 1,
            toCashAccountId = 3, toAmountMinor = 1000), accounts, AppSettings(Currency.of("CNY")), FxContext(), emptyMap(), zone))
    }

    @Test fun pictures_brought_along_are_new_to_the_bill() {
        // The page lets go of them if it closes unsaved. (The form's encoding needs Android; the UI tests open it.)
        val form = BillPrefill.form(BillType.EXPENSE, 1250, "USD", time, accounts, "CNY", cashAccountId = 1, categoryId = 10,
            note = "Book", images = listOf(BillImage("a".repeat(64), 10, 2, 2)))
        assertEquals(listOf("a".repeat(64)), form.addedImages)
    }
}
