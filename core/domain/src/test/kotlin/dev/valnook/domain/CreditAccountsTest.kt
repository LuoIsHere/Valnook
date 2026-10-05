package dev.valnook.domain

import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditBillingFocus
import dev.valnook.domain.calculation.CreditLimitCalculator
import dev.valnook.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate

class CreditAccountsTest {
    private val cny = Currency.of("CNY")

    private fun credit(id: Long, balance: Long, limit: Long? = 20_000, source: Long? = null,
        parentId: Long = 1) =
        CashAccount(parentId, cny, balance, 1, id, "Card $id", creditProfile = CreditAccountProfile(
            limit, 12, CreditDueRule.AfterStatementDays(20), source))

    @Test fun creditLimitSumsDebtsWithoutOffsettingOverpayments() {
        val accounts = listOf(credit(1, -12_000), credit(2, -3_000, null, 1), credit(3, 3_000, null, 1))
        val result = CreditLimitCalculator.calculate(3, accounts)
        assertEquals(BigDecimal("15000"), result.usedLimitMinor)
        assertEquals(BigDecimal("5000"), result.availableLimitMinor)
        assertEquals(BigDecimal("3000"), result.ownOverpaymentMinor)
    }

    @Test fun overLimitAndSharedRootAreDerived() {
        val accounts = listOf(credit(1, -12_000), credit(2, -8_320, null, 1))
        val result = CreditLimitCalculator.calculate(2, accounts)
        assertEquals(1, result.rootAccountId)
        assertEquals(BigDecimal("320"), result.overLimitMinor)
    }

    @Test fun sharedLimitRejectsAParentFromAnotherMainAccount() {
        val accounts = listOf(credit(1, -12_000), credit(2, -3_000, null, 1, parentId = 2))
        val error = assertThrows(DomainException::class.java) {
            CreditLimitCalculator.calculate(2, accounts)
        }
        assertEquals(ErrorCode.CREDIT_SOURCE_PARENT, error.code)
        val rootError = assertThrows(DomainException::class.java) {
            CreditLimitCalculator.calculate(1, accounts)
        }
        assertEquals(ErrorCode.CREDIT_SOURCE_PARENT, rootError.code)
    }

    @Test fun billingRulesClampMonthsAndCrossYears() {
        assertEquals(LocalDate.of(2028, 2, 29), CreditBillingCalendar.calculate(
            LocalDate.of(2028, 2, 29), 31, CreditDueRule.AfterStatementDays(20)).latestStatement)
        assertEquals(LocalDate.of(2027, 3, 5), CreditBillingCalendar.dueDate(
            LocalDate.of(2027, 2, 28), CreditDueRule.FixedDayOfMonth(5)))
        assertEquals(LocalDate.of(2027, 1, 5), CreditBillingCalendar.dueDate(
            LocalDate.of(2026, 12, 12), CreditDueRule.FixedDayOfMonth(5)))
        assertEquals(LocalDate.of(2027, 2, 28), CreditBillingCalendar.dueDate(
            LocalDate.of(2027, 2, 5), CreditDueRule.FixedDayOfMonth(31)))
        assertEquals(LocalDate.of(2026, 10, 20), CreditBillingCalendar.dueDate(
            LocalDate.of(2026, 10, 5), CreditDueRule.FixedDayOfMonth(20)))
        assertEquals(LocalDate.of(2026, 11, 14), CreditBillingCalendar.dueDate(
            LocalDate.of(2026, 10, 25), CreditDueRule.AfterStatementDays(20)))
    }

    @Test fun billingFocusHandlesDueAndStatementToday() {
        val due = CreditBillingCalendar.calculate(LocalDate.of(2026, 10, 20), 12,
            CreditDueRule.AfterStatementDays(20))
        assertEquals(CreditBillingFocus.DUE, due.focus)
        assertEquals(12, due.daysRemaining)
        val statement = CreditBillingCalendar.calculate(LocalDate.of(2026, 10, 12), 12,
            CreditDueRule.FixedDayOfMonth(5))
        assertEquals(CreditBillingFocus.STATEMENT, statement.focus)
        assertEquals(0, statement.daysRemaining)
        val dueToday = CreditBillingCalendar.calculate(LocalDate.of(2026, 11, 14), 25,
            CreditDueRule.AfterStatementDays(20))
        assertEquals(CreditBillingFocus.DUE, dueToday.focus)
        assertEquals(0, dueToday.daysRemaining)
    }

    @Test fun invalidBillingRulesAreRejected() {
        assertThrows(DomainException::class.java) {
            CreditBillingCalendar.calculate(LocalDate.of(2026, 1, 1), 0,
                CreditDueRule.AfterStatementDays(20))
        }
        assertThrows(DomainException::class.java) {
            CreditBillingCalendar.calculate(LocalDate.of(2026, 1, 1), 1,
                CreditDueRule.AfterStatementDays(366))
        }
    }
}
