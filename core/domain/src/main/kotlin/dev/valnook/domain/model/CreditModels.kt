package dev.valnook.domain.model

import java.math.BigDecimal

enum class BalanceAccountType { SAVINGS, CREDIT }

const val MAX_CREDIT_DUE_OFFSET_DAYS = 365

sealed interface CreditDueRule {
    val value: Int

    data class AfterStatementDays(val days: Int) : CreditDueRule {
        override val value: Int get() = days
    }

    data class FixedDayOfMonth(val day: Int) : CreditDueRule {
        override val value: Int get() = day
    }
}

/**
 * [statementDayIncluded]: whether spending on the statement day itself goes on that day's statement (on) or on the
 * next one (off); banks differ.
 */
data class CreditAccountProfile(
    val creditLimitMinor: Long?,
    val statementDay: Int,
    val dueRule: CreditDueRule,
    val limitSourceAccountId: Long?,
    val statementDayIncluded: Boolean = true
)

data class CreditAccountInput(
    val creditLimitMinor: Long?,
    val statementDay: Int,
    val dueRule: CreditDueRule,
    val limitSourceAccountId: Long?,
    val statementDayIncluded: Boolean = true
)

data class CreditLimitSummary(
    val accountId: Long,
    val rootAccountId: Long,
    val memberAccountIds: List<Long>,
    val ownDebtMinor: BigDecimal,
    val ownOverpaymentMinor: BigDecimal,
    val totalLimitMinor: BigDecimal,
    val usedLimitMinor: BigDecimal,
    val availableLimitMinor: BigDecimal,
    val overLimitMinor: BigDecimal
)

/**
 * A billing cycle's bills on one credit sub-account, in its currency's minor units, each as a positive amount: what
 * left it ([spentMinor], expenses and transfers out with their fees), what came back as refunds or reimbursements, and
 * what else came in ([repaidMinor], transfers in and income). Balance edits made by hand are not bills and are left out.
 */
data class CreditCycleTotals(val spentMinor: Long, val refundedMinor: Long, val repaidMinor: Long) {
    /** Spending less refunds: what the cycle adds to the statement. */
    val netSpentMinor: Long get() = Math.subtractExact(spentMinor, refundedMinor)
}
