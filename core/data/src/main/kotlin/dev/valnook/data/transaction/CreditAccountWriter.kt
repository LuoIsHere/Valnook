package dev.valnook.data.transaction

import dev.valnook.data.database.CreditAccountProfileEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*

internal class CreditAccountWriter(private val db: ValnookDatabase) {
    suspend fun validateAndBuild(
        accountId: Long,
        currencyCode: String,
        type: BalanceAccountType,
        input: CreditAccountInput?,
        existingAccount: Boolean
    ): CreditAccountProfileEntity? {
        val old = db.credit().profile(accountId)
        if (existingAccount && ((old == null) != (type == BalanceAccountType.SAVINGS))) {
            throw DomainException(ErrorCode.INVALID_ACCOUNT_TYPE)
        }
        if (type == BalanceAccountType.SAVINGS) {
            if (input != null) throw DomainException(ErrorCode.INVALID_ACCOUNT_TYPE)
            return null
        }
        val credit = input ?: throw DomainException(ErrorCode.INVALID_ACCOUNT_TYPE)
        if (credit.statementDay !in 1..31) throw DomainException(ErrorCode.INVALID_STATEMENT_DAY)
        val (dueType, dueValue) = when (val rule = credit.dueRule) {
            is CreditDueRule.AfterStatementDays -> {
                if (rule.days !in 1..MAX_CREDIT_DUE_OFFSET_DAYS) throw DomainException(ErrorCode.INVALID_DUE_RULE)
                "AFTER_STATEMENT_DAYS" to rule.days
            }
            is CreditDueRule.FixedDayOfMonth -> {
                if (rule.day !in 1..31) throw DomainException(ErrorCode.INVALID_DUE_RULE)
                "FIXED_DAY_OF_MONTH" to rule.day
            }
        }
        val sourceId = credit.limitSourceAccountId
        val localLimit = credit.creditLimitMinor
        if (sourceId == null) {
            if (localLimit == null || localLimit <= 0) {
                throw DomainException(ErrorCode.INVALID_CREDIT_LIMIT)
            }
        } else {
            if (localLimit != null) throw DomainException(ErrorCode.INVALID_CREDIT_LIMIT)
            if (sourceId == accountId) throw DomainException(ErrorCode.CREDIT_SOURCE_CYCLE)
            if (db.credit().dependents(accountId).isNotEmpty()) throw DomainException(ErrorCode.CREDIT_LIMIT_IN_USE)
            val sourceAccount = db.cash().cashAccount(sourceId)
                ?: throw DomainException(ErrorCode.CREDIT_SOURCE_INVALID)
            val targetAccount = db.cash().cashAccount(accountId)
                ?: throw DomainException(ErrorCode.CREDIT_SOURCE_INVALID)
            val source = db.credit().profile(sourceId)
                ?: throw DomainException(ErrorCode.CREDIT_SOURCE_INVALID)
            if (source.limit_source_account_id != null) throw DomainException(ErrorCode.CREDIT_SOURCE_CHAIN)
            if (source.credit_limit_minor == null || source.credit_limit_minor <= 0) {
                throw DomainException(ErrorCode.CREDIT_SOURCE_INVALID)
            }
            if (sourceAccount.savings_account_id != targetAccount.savings_account_id) {
                throw DomainException(ErrorCode.CREDIT_SOURCE_PARENT)
            }
            if (sourceAccount.currency_code != currencyCode) throw DomainException(ErrorCode.CREDIT_SOURCE_CURRENCY)
        }
        return CreditAccountProfileEntity(accountId, localLimit, credit.statementDay,
            dueType, dueValue, sourceId)
    }

    suspend fun save(value: CreditAccountProfileEntity?) {
        if (value != null) db.credit().upsert(value)
    }
}
