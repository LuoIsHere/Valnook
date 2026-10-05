package dev.valnook.domain.calculation

import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.MAX_CREDIT_DUE_OFFSET_DAYS
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class CreditBillingFocus { STATEMENT, DUE }

data class CreditBillingStatus(
    val latestStatement: LocalDate,
    val latestStatementDue: LocalDate,
    val nextStatement: LocalDate,
    val focus: CreditBillingFocus,
    val focusDate: LocalDate,
    val daysRemaining: Long
)

object CreditBillingCalendar {
    fun calculate(today: LocalDate, statementDay: Int, dueRule: CreditDueRule): CreditBillingStatus {
        validate(statementDay, dueRule)
        val thisMonth = date(YearMonth.from(today), statementDay)
        val latest = if (thisMonth <= today) thisMonth else date(YearMonth.from(today).minusMonths(1), statementDay)
        val due = dueDate(latest, dueRule)
        val nextStatement = date(YearMonth.from(latest).plusMonths(1), statementDay)
        val focus = when {
            latest == today -> CreditBillingFocus.STATEMENT
            due >= today -> CreditBillingFocus.DUE
            else -> CreditBillingFocus.STATEMENT
        }
        val focusDate = when {
            latest == today -> latest
            focus == CreditBillingFocus.DUE -> due
            else -> nextStatement
        }
        return CreditBillingStatus(latest, due, nextStatement, focus, focusDate,
            ChronoUnit.DAYS.between(today, focusDate))
    }

    fun dueDate(statementDate: LocalDate, rule: CreditDueRule): LocalDate {
        validate(statementDate.dayOfMonth, rule, validateStatement = false)
        return when (rule) {
            is CreditDueRule.AfterStatementDays -> statementDate.plusDays(rule.days.toLong())
            is CreditDueRule.FixedDayOfMonth -> {
                val sameMonth = date(YearMonth.from(statementDate), rule.day)
                if (sameMonth > statementDate) sameMonth else date(YearMonth.from(statementDate).plusMonths(1), rule.day)
            }
        }
    }

    private fun date(month: YearMonth, day: Int): LocalDate = month.atDay(day.coerceAtMost(month.lengthOfMonth()))

    private fun validate(statementDay: Int, rule: CreditDueRule, validateStatement: Boolean = true) {
        if (validateStatement && statementDay !in 1..31) throw DomainException(ErrorCode.INVALID_STATEMENT_DAY)
        when (rule) {
            is CreditDueRule.AfterStatementDays -> if (rule.days !in 1..MAX_CREDIT_DUE_OFFSET_DAYS)
                throw DomainException(ErrorCode.INVALID_DUE_RULE)
            is CreditDueRule.FixedDayOfMonth -> if (rule.day !in 1..31)
                throw DomainException(ErrorCode.INVALID_DUE_RULE)
        }
    }
}
