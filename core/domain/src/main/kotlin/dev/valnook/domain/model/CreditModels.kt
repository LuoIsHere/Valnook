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

data class CreditAccountProfile(
    val creditLimitMinor: Long?,
    val statementDay: Int,
    val dueRule: CreditDueRule,
    val limitSourceAccountId: Long?
)

data class CreditAccountInput(
    val creditLimitMinor: Long?,
    val statementDay: Int,
    val dueRule: CreditDueRule,
    val limitSourceAccountId: Long?
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
