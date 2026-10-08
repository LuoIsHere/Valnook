package dev.valnook.domain.calculation

import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CreditLimitSummary
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import java.math.BigDecimal

object CreditLimitCalculator {
    fun calculate(accountId: Long, accounts: List<CashAccount>): CreditLimitSummary {
        val account = accounts.firstOrNull { it.id == accountId }
            ?: throw DomainException(ErrorCode.NOT_FOUND)
        val profile = account.creditProfile ?: throw DomainException(ErrorCode.INVALID_ACCOUNT_TYPE)
        val rootId = profile.limitSourceAccountId ?: account.id
        val root = accounts.firstOrNull { it.id == rootId }
            ?: throw DomainException(ErrorCode.CREDIT_SOURCE_INVALID)
        val rootProfile = root.creditProfile ?: throw DomainException(ErrorCode.CREDIT_SOURCE_INVALID)
        if (rootProfile.limitSourceAccountId != null) throw DomainException(ErrorCode.CREDIT_SOURCE_CHAIN)
        if (root.account_id != account.account_id) throw DomainException(ErrorCode.CREDIT_SOURCE_PARENT)
        if (root.currency != account.currency) throw DomainException(ErrorCode.CREDIT_SOURCE_CURRENCY)
        val total = rootProfile.creditLimitMinor?.takeIf { it >= 0 }?.let(BigDecimal::valueOf)
            ?: throw DomainException(ErrorCode.INVALID_CREDIT_LIMIT)
        val members = accounts.filter { candidate ->
            candidate.id == rootId || candidate.creditProfile?.limitSourceAccountId == rootId
        }
        if (members.any { it.account_id != root.account_id }) {
            throw DomainException(ErrorCode.CREDIT_SOURCE_PARENT)
        }
        if (members.any { it.currency != root.currency }) {
            throw DomainException(ErrorCode.CREDIT_SOURCE_CURRENCY)
        }
        val used = members.fold(BigDecimal.ZERO) { sum, member -> sum + debt(member.balance_minor) }
        val ownDebt = debt(account.balance_minor)
        val ownOverpayment = BigDecimal.valueOf(account.balance_minor).max(BigDecimal.ZERO)
        val available = total - used
        return CreditLimitSummary(account.id, rootId, members.map { it.id }, ownDebt, ownOverpayment,
            total, used, available, used.subtract(total).max(BigDecimal.ZERO))
    }

    private fun debt(balanceMinor: Long): BigDecimal = BigDecimal.valueOf(balanceMinor)
        .negate().max(BigDecimal.ZERO)
}
