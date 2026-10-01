package dev.valnook.data.transaction

import dev.valnook.data.database.*
import dev.valnook.data.repository.valid_name
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*

internal class AccountCommandHandler(private val db: ValnookDatabase, private val cash: CashWriter,
    private val fault: (TransactionPoint) -> Unit) {
    suspend fun save(command: SaveAccount, now: Long): OperationResult {
        val name = valid_name(command.name)
        if (command.note.length > 2000) throw DomainException(ErrorCode.FORMAT)
        val rows = command.cashChanges.sortedBy { Currency.of(it.currencyCode).code }
        if (rows.map { Currency.of(it.currencyCode).code }.distinct().size != rows.size)
            throw DomainException(ErrorCode.DUPLICATE_CURRENCY)
        val oldAccount = command.accountId?.let { db.accounts().account(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
        if (oldAccount?.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        val accountId = oldAccount?.id ?: db.accounts().insert_account(AccountEntity(name = name, note = command.note,
            created_at_ms = now, updated_at_ms = now))
        // Validate every balance before writing any of them; all still roll back on downstream failure.
        for (row in rows) {
            val currency = cash.currency(row.currencyCode)
            R.check_nonnegative(row.balanceMinor)
            if (db.cash().cash_one(accountId, currency.code)?.revision != row.expectedRevision)
                throw DomainException(ErrorCode.STALE_BALANCE)
        }
        if (oldAccount != null) {
            R.add(oldAccount.revision, 1)
            if (db.accounts().edit_account(accountId, name, command.note, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        for (row in rows) {
            val code = Currency.of(row.currencyCode).code
            val old = db.cash().cash_one(accountId, code)
            val delta = R.replace_contribution(row.balanceMinor, old?.balance_minor ?: 0, 0)
            // A first zero save establishes a row and its currency lock. Unchanged existing rows do not write.
            if (old == null || delta != 0L) {
                cash.change(command.operation_id, accountId, code, delta, "CASH_SET", now)
                cash.entry(command.operation_id, accountId, code, "CASH_SET", null, delta, now, now)
            }
        }
        return OperationResult("ACCOUNT", accountId)
    }
    suspend fun setBalance(command: SetCashBalance, now: Long): OperationResult {
        cash.requireAccount(command.account_id)
        val code = cash.currency(command.currency_code).code
        R.check_nonnegative(command.balance_minor)
        val old = db.cash().cash_one(command.account_id, code)
        if (old?.revision != command.expected_revision) throw DomainException(ErrorCode.STALE_BALANCE)
        val delta = R.replace_contribution(command.balance_minor, old?.balance_minor ?: 0, 0)
        cash.change(command.operation_id, command.account_id, code, delta, "CASH_SET", now)
        val id = cash.entry(command.operation_id, command.account_id, code, "CASH_SET", null, delta, now, now)
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("CASH_ENTRY", id)
    }
    suspend fun editEntry(command: EditCashEntry, now: Long): OperationResult {
        val old = db.cash().cash_entry(command.entry_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (old.is_deleted || old.revision != command.expected_revision) throw DomainException(ErrorCode.STALE_RECORD)
        if (old.source_kind != "CASH_SET") throw DomainException(ErrorCode.SOURCE_RECORD)
        if (command.note.length > 2000) throw DomainException(ErrorCode.FORMAT)
        cash.currency(old.currency_code)
        R.add(old.revision, 1)
        if (db.cash().edit_entry(old.id, old.revision, command.delta_minor, command.occurred_at_ms,
                command.note.trim(), false, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        cash.change(command.operation_id, old.savings_account_id, old.currency_code,
            R.replace_contribution(0, old.delta_minor, command.delta_minor), "CASH_EDIT", now)
        return OperationResult("CASH_ENTRY", old.id)
    }
}
