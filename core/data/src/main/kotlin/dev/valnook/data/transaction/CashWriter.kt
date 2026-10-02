package dev.valnook.data.transaction

import dev.valnook.data.database.CashEntity
import dev.valnook.data.database.CashEntryEntity
import dev.valnook.data.database.MovementEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.money.DecimalRules as R

/** Cash mutations run only inside RoomFinancialCommands' transaction. */
internal class CashWriter(
    private val db: ValnookDatabase,
    private val fault: (TransactionPoint) -> Unit
) {
    private val dao = db.cash()

    suspend fun requireAccount(id: Long) {
        if (db.accounts().account(id) == null) throw DomainException(ErrorCode.NOT_FOUND)
    }

    suspend fun currency(code: String): Currency {
        val value = Currency.of(code)
        val stored = db.accounts().currency(value.code) ?: throw DomainException(ErrorCode.CURRENCY)
        if (stored.fraction_digits != value.fraction_digits) throw DomainException(ErrorCode.CURRENCY)
        return value
    }

    suspend fun requireCashAccount(cashAccountId: Long, accountId: Long, currencyCode: String): CashEntity {
        val account = dao.cashAccount(cashAccountId) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
        if (account.savings_account_id != accountId || account.currency_code != Currency.of(currencyCode).code) {
            throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
        }
        return account
    }

    suspend fun resolveLink(accountId: Long, currencyCode: String, linked: Boolean, requestedId: Long?): Long? {
        if (!linked) {
            if (requestedId != null) throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
            return null
        }
        if (requestedId != null) return requireCashAccount(requestedId, accountId, currencyCode).id
        val candidates = dao.cashCandidates(accountId, Currency.of(currencyCode).code)
        if (candidates.size != 1) throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
        return candidates.single().id
    }

    suspend fun createAccount(
        operationId: String,
        accountId: Long,
        name: String,
        note: String,
        currencyCode: String,
        balanceMinor: Long,
        now: Long
    ): CashEntity {
        requireAccount(accountId)
        val code = currency(currencyCode).code
        val entity = CashEntity(accountId, code, balanceMinor, 1, now, name = name, note = note,
            currency_locked = true, created_at_ms = now)
        val id = dao.insert_cash(entity)
        dao.insert_movement(MovementEntity(operation_id = operationId, savings_account_id = accountId,
            currency_code = code, cash_account_id = id, reason = "CASH_SET", delta_minor = balanceMinor,
            balance_before_minor = 0, balance_after_minor = balanceMinor, created_at_ms = now))
        fault(TransactionPoint.AFTER_CASH)
        return entity.copy(id = id)
    }

    suspend fun change(
        operationId: String,
        cashAccountId: Long,
        deltaMinor: Long,
        reason: String,
        now: Long
    ): Long {
        val old = dao.cashAccount(cashAccountId) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
        val after = R.add(old.balance_minor, deltaMinor)
        if (dao.updateCashBalance(old.id, after, R.add(old.revision, 1), old.revision, now) != 1) {
            throw DomainException(ErrorCode.STALE_BALANCE)
        }
        val id = dao.insert_movement(MovementEntity(operation_id = operationId,
            savings_account_id = old.savings_account_id, currency_code = old.currency_code,
            cash_account_id = old.id, reason = reason, delta_minor = deltaMinor,
            balance_before_minor = old.balance_minor, balance_after_minor = after, created_at_ms = now))
        fault(TransactionPoint.AFTER_CASH)
        return id
    }

    suspend fun applyPlan(
        operationId: String,
        oldCashAccountId: Long?,
        oldDelta: Long,
        newCashAccountId: Long?,
        newDelta: Long,
        reason: String,
        now: Long
    ) {
        applyChanges(operationId, listOf(oldCashAccountId to -oldDelta, newCashAccountId to newDelta), reason, now)
    }

    suspend fun applyChanges(
        operationId: String,
        changes: List<Pair<Long?, Long>>,
        reason: String,
        now: Long
    ) {
        val adjustments = linkedMapOf<Long, Long>()
        changes.forEach { (cashAccountId, delta) ->
            if (cashAccountId != null) adjustments[cashAccountId] = R.add(adjustments[cashAccountId] ?: 0, delta)
        }
        val rows = adjustments.map { (id, delta) ->
            val account = dao.cashAccount(id) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
            val after = R.add(account.balance_minor, delta)
            Triple(account, delta, after)
        }
        rows.filter { it.second != 0L }.forEach { (account, delta, _) ->
            change(operationId, account.id, delta, reason, now)
        }
    }

    suspend fun entry(
        operationId: String,
        cashAccountId: Long,
        kind: String,
        sourceId: Long?,
        delta: Long,
        occurred: Long,
        now: Long
    ): Long {
        val account = dao.cashAccount(cashAccountId) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
        return dao.insert_entry(CashEntryEntity(original_operation_id = operationId,
            savings_account_id = account.savings_account_id, currency_code = account.currency_code,
            cash_account_id = account.id, source_kind = kind, source_id = sourceId, delta_minor = delta,
            occurred_at_ms = occurred, note = "", revision = 1, is_deleted = false,
            created_at_ms = now, updated_at_ms = now))
    }
}
