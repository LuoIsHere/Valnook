package dev.valnook.data.transaction

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R

/** Called only inside the command executor's transaction. A saved row permanently fixes currency. */
internal class CashWriter(private val db: ValnookDatabase, private val fault: (TransactionPoint) -> Unit) {
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
    suspend fun change(operationId: String, accountId: Long, code: String, deltaMinor: Long,
        reason: String, now: Long): Long {
        val old = dao.cash_one(accountId, code)
        val before = old?.balance_minor ?: 0L
        val after = R.add(before, deltaMinor)
        if (after < 0) throw DomainException(ErrorCode.INSUFFICIENT_CASH)
        if (old == null) dao.insert_cash(CashEntity(accountId, code, after, 1, now))
        else if (dao.update_cash(accountId, code, after, R.add(old.revision, 1), old.revision, now) != 1)
            throw DomainException(ErrorCode.STALE_BALANCE)
        val id = dao.insert_movement(MovementEntity(operation_id = operationId, savings_account_id = accountId,
            currency_code = code, reason = reason, delta_minor = deltaMinor, balance_before_minor = before,
            balance_after_minor = after, created_at_ms = now))
        fault(TransactionPoint.AFTER_CASH)
        return id
    }
    suspend fun entry(operationId: String, accountId: Long, code: String, kind: String,
        sourceId: Long?, delta: Long, occurred: Long, now: Long): Long =
        dao.insert_entry(CashEntryEntity(original_operation_id = operationId, savings_account_id = accountId,
            currency_code = code, source_kind = kind, source_id = sourceId, delta_minor = delta,
            occurred_at_ms = occurred, note = "", revision = 1, is_deleted = false, created_at_ms = now, updated_at_ms = now))
}
