package dev.valnook.domain.calculation

import dev.valnook.domain.model.CreditAccountProfile
import dev.valnook.domain.model.CreditCycleTotals
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.MAX_CREDIT_DUE_OFFSET_DAYS
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import dev.valnook.domain.repository.LedgerPeriod
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

/**
 * One billing cycle, [start] through [end], whose statement comes out on [statement] and is to be paid by [due].
 * When the statement day's own spending goes on that statement, the cycle runs from the day after the previous
 * statement date through the statement date; otherwise from the previous statement date to the day before this one.
 * Known by its statement month.
 */
data class CreditCycle(val start: LocalDate, val end: LocalDate, val statement: LocalDate, val due: LocalDate) {
    val month: YearMonth get() = YearMonth.from(statement)
    fun period(zone: ZoneId): LedgerPeriod = LedgerPeriod.days(start, end, zone)
}

/**
 * The card's cycle so far and the statement before it. [previousOwedMinor] is what that statement left owing (the
 * balance at the end of its statement day, as a positive debt), null when the sub-account did not exist yet.
 */
data class CreditCycleSummary(val current: CreditCycle, val currentTotals: CreditCycleTotals,
    val previous: CreditCycle, val previousOwedMinor: Long?) {
    /** Paid in since the previous statement: what this cycle brought in other than refunds. */
    val previousRepaidMinor: Long get() = currentTotals.repaidMinor
    /** Still to pay on the previous statement, never below zero; null when it is not known. */
    val previousRemainingMinor: Long? get() = previousOwedMinor?.let { maxOf(0L, Math.subtractExact(it, previousRepaidMinor)) }

    companion object {
        /** [balanceAtStatement] is the balance at the end of the previous statement day; negative is owing. */
        fun of(current: CreditCycle, totals: CreditCycleTotals, previous: CreditCycle, balanceAtStatement: Long?) =
            CreditCycleSummary(current, totals, previous, balanceAtStatement?.let { if (it < 0) Math.negateExact(it) else 0L })
    }
}

object CreditBillingCalendar {
    /** The cycle whose statement falls in [statementMonth]; see [CreditCycle] for [statementDayIncluded]. */
    fun cycle(statementMonth: YearMonth, statementDay: Int, dueRule: CreditDueRule,
        statementDayIncluded: Boolean = true): CreditCycle {
        validate(statementDay, dueRule)
        val statement = date(statementMonth, statementDay)
        val previous = date(statementMonth.minusMonths(1), statementDay)
        return if (statementDayIncluded) CreditCycle(previous.plusDays(1), statement, statement, dueDate(statement, dueRule))
            else CreditCycle(previous, statement.minusDays(1), statement, dueDate(statement, dueRule))
    }

    fun cycle(statementMonth: YearMonth, profile: CreditAccountProfile) =
        cycle(statementMonth, profile.statementDay, profile.dueRule, profile.statementDayIncluded)

    /** The cycle [today] is in: the first one that has not ended before today. */
    fun currentCycle(today: LocalDate, statementDay: Int, dueRule: CreditDueRule,
        statementDayIncluded: Boolean = true): CreditCycle {
        val thisMonth = cycle(YearMonth.from(today), statementDay, dueRule, statementDayIncluded)
        return if (thisMonth.end >= today) thisMonth
            else cycle(YearMonth.from(today).plusMonths(1), statementDay, dueRule, statementDayIncluded)
    }

    fun currentCycle(today: LocalDate, profile: CreditAccountProfile) =
        currentCycle(today, profile.statementDay, profile.dueRule, profile.statementDayIncluded)

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
