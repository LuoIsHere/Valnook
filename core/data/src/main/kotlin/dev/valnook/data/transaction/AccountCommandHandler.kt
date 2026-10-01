package dev.valnook.data.transaction

import dev.valnook.data.database.AccountEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.valid_name
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.EditCashEntry
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SetCashBalance

internal class AccountCommandHandler(
    private val db: ValnookDatabase,
    private val cash: CashWriter,
    private val fault: (TransactionPoint) -> Unit
) {
    suspend fun save(command: SaveAccount, now: Long): OperationResult {
        val accountName = valid_name(command.name)
        if (command.note.length > 2000) throw DomainException(ErrorCode.FORMAT)
        if (command.cashChanges.mapNotNull { it.cashAccountId }.distinct().size !=
            command.cashChanges.count { it.cashAccountId != null }) {
            throw DomainException(ErrorCode.OPERATION_CONFLICT)
        }
        val oldAccount = command.accountId?.let {
            db.accounts().account(it) ?: throw DomainException(ErrorCode.NOT_FOUND)
        }
        if (oldAccount?.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        val accountId = oldAccount?.id ?: db.accounts().insert_account(AccountEntity(name = accountName,
            note = command.note, created_at_ms = now, updated_at_ms = now))

        val validated = command.cashChanges.map { row ->
            val currency = cash.currency(row.currencyCode)
            val name = valid_name(row.name)
            if (row.note.length > 2000) throw DomainException(ErrorCode.FORMAT)
            R.check_nonnegative(row.balanceMinor)
            val old = row.cashAccountId?.let {
                cash.requireCashAccount(it, accountId, currency.code)
            }
            if (old?.revision != row.expectedRevision) throw DomainException(ErrorCode.STALE_BALANCE)
            ValidatedCashChange(row, name, currency.code, old)
        }

        if (oldAccount != null) {
            R.add(oldAccount.revision, 1)
            if (db.accounts().edit_account(accountId, accountName, command.note, now) != 1) {
                throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
        fault(TransactionPoint.AFTER_BUSINESS)

        validated.forEach { value ->
            val old = value.old
            if (old == null) {
                val created = cash.createAccount(command.operation_id, accountId, value.name, value.row.note,
                    value.currencyCode, value.row.balanceMinor, now)
                cash.entry(command.operation_id, created.id, CashSource.CASH_SET.name, null,
                    value.row.balanceMinor, now, now)
            } else {
                val metadataChanged = old.name != value.name || old.note != value.row.note
                val balanceChanged = old.balance_minor != value.row.balanceMinor
                if (metadataChanged || balanceChanged) {
                    if (db.cash().updateCashAccount(old.id, old.revision, value.name, value.row.note,
                            value.row.balanceMinor, now) != 1) throw DomainException(ErrorCode.STALE_BALANCE)
                    if (balanceChanged) {
                        val delta = R.replace_contribution(value.row.balanceMinor, old.balance_minor, 0)
                        db.cash().insert_movement(dev.valnook.data.database.MovementEntity(
                            operation_id = command.operation_id, savings_account_id = accountId,
                            currency_code = old.currency_code, cash_account_id = old.id, reason = "CASH_SET",
                            delta_minor = delta, balance_before_minor = old.balance_minor,
                            balance_after_minor = value.row.balanceMinor, created_at_ms = now))
                        fault(TransactionPoint.AFTER_CASH)
                        cash.entry(command.operation_id, old.id, CashSource.CASH_SET.name, null, delta, now, now)
                    }
                }
            }
        }
        return OperationResult("ACCOUNT", accountId)
    }

    suspend fun setBalance(command: SetCashBalance, now: Long): OperationResult {
        cash.requireAccount(command.account_id)
        val code = cash.currency(command.currency_code).code
        R.check_nonnegative(command.balance_minor)
        val old = command.cashAccountId?.let { cash.requireCashAccount(it, command.account_id, code) }
            ?: db.cash().cashCandidates(command.account_id, code).singleOrNull()
        if (old == null) {
            val created = cash.createAccount(command.operation_id, command.account_id,
                command.name.ifBlank { code }, command.note, code, command.balance_minor, now)
            val entryId = cash.entry(command.operation_id, created.id, CashSource.CASH_SET.name, null,
                command.balance_minor, now, now)
            fault(TransactionPoint.AFTER_BUSINESS)
            return OperationResult("CASH_ENTRY", entryId)
        }
        if (old.revision != command.expected_revision) throw DomainException(ErrorCode.STALE_BALANCE)
        val delta = R.replace_contribution(command.balance_minor, old.balance_minor, 0)
        cash.change(command.operation_id, old.id, delta, "CASH_SET", now)
        val id = cash.entry(command.operation_id, old.id, CashSource.CASH_SET.name, null, delta, now, now)
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("CASH_ENTRY", id)
    }

    suspend fun editEntry(command: EditCashEntry, now: Long): OperationResult {
        val old = db.cash().cash_entry(command.entry_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (old.is_deleted || old.revision != command.expected_revision) throw DomainException(ErrorCode.STALE_RECORD)
        if (old.source_kind != CashSource.CASH_SET.name) throw DomainException(ErrorCode.SOURCE_RECORD)
        if (command.note.length > 2000) throw DomainException(ErrorCode.FORMAT)
        val account = db.cash().cashAccount(old.cash_account_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        R.add(old.revision, 1)
        if (db.cash().editEntry(old.id, old.revision, account.id, account.savings_account_id,
                account.currency_code, command.delta_minor, command.occurred_at_ms, command.note.trim(), false, now) != 1) {
            throw DomainException(ErrorCode.STALE_RECORD)
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        cash.change(command.operation_id, account.id,
            R.replace_contribution(0, old.delta_minor, command.delta_minor), "CASH_EDIT", now)
        return OperationResult("CASH_ENTRY", old.id)
    }

    private data class ValidatedCashChange(
        val row: dev.valnook.domain.repository.CashBalanceChange,
        val name: String,
        val currencyCode: String,
        val old: dev.valnook.data.database.CashEntity?
    )
}
