package dev.valnook.data.transaction

import dev.valnook.data.database.CashEntryEntity
import dev.valnook.data.database.DepositEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CloseTermDeposit
import dev.valnook.domain.repository.EditTermDeposit
import dev.valnook.domain.repository.OpenTermDeposit
import java.time.Clock
import java.time.LocalDate

internal class DepositCommandHandler(
    private val db: ValnookDatabase,
    private val cash: CashWriter,
    private val clock: Clock,
    private val fault: (TransactionPoint) -> Unit
) {
    private val dao = db.deposits()

    suspend fun open(command: OpenTermDeposit, now: Long): OperationResult {
        cash.requireAccount(command.account_id)
        val code = cash.currency(command.currency_code).code
        val targetId = cash.resolveLink(command.account_id, code, command.cash_linked, command.cashAccountId)
        val interest = R.interest(command.principal_minor, command.annual_rate_percent_e8,
            command.start_epoch_day, command.end_epoch_day)
        val id = dao.insert_deposit(DepositEntity(savings_account_id = command.account_id,
            currency_code = code, principal_minor = command.principal_minor,
            annual_rate_percent_e8 = command.annual_rate_percent_e8,
            start_epoch_day = command.start_epoch_day, end_epoch_day = command.end_epoch_day,
            expected_interest_minor = interest, open_cash_linked = targetId != null,
            open_cash_account_id = targetId, open_operation_id = command.operation_id,
            created_at_ms = now, updated_at_ms = now))
        fault(TransactionPoint.AFTER_BUSINESS)
        if (targetId != null) {
            cash.change(command.operation_id, targetId, -command.principal_minor, "TERM_OPEN", now)
            cash.entry(command.operation_id, targetId, "TERM_OPEN", id, -command.principal_minor,
                businessTime(command.start_epoch_day), now)
        }
        return OperationResult("TERM_DEPOSIT", id)
    }

    suspend fun close(command: CloseTermDeposit, now: Long): OperationResult {
        val value = dao.deposit(command.deposit_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (value.status != "OPEN") throw DomainException(ErrorCode.ALREADY_CLOSED)
        if (LocalDate.now(clock).toEpochDay() < value.end_epoch_day) throw DomainException(ErrorCode.NOT_MATURED)
        val targetId = cash.resolveLink(value.savings_account_id, value.currency_code,
            command.cash_linked, command.cashAccountId)
        if (dao.close_deposit(value.id, targetId, command.operation_id, now) != 1) {
            throw DomainException(ErrorCode.ALREADY_CLOSED)
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        if (targetId != null) {
            val returned = R.add(value.principal_minor, value.expected_interest_minor)
            cash.change(command.operation_id, targetId, returned, "TERM_CLOSE", now)
            cash.entry(command.operation_id, targetId, "TERM_CLOSE", value.id, returned, now, now)
        }
        return OperationResult("TERM_DEPOSIT", value.id)
    }

    suspend fun edit(command: EditTermDeposit, now: Long): OperationResult {
        val old = dao.deposit(command.deposit_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (old.revision != command.expected_revision) throw DomainException(ErrorCode.STALE_RECORD)
        val closed = old.status == "CLOSED"
        if (closed != (command.close_cash_linked != null)) throw DomainException(ErrorCode.FORMAT)
        if (closed && LocalDate.now(clock).toEpochDay() < command.end_epoch_day) {
            throw DomainException(ErrorCode.NOT_MATURED)
        }
        val openTarget = cash.resolveLink(old.savings_account_id, old.currency_code,
            command.open_cash_linked, command.openCashAccountId)
        val closeTarget = if (closed) cash.resolveLink(old.savings_account_id, old.currency_code,
            command.close_cash_linked == true, command.closeCashAccountId) else null
        val interest = R.interest(command.principal_minor, command.annual_rate_percent_e8,
            command.start_epoch_day, command.end_epoch_day)
        val oldReturn = R.add(old.principal_minor, old.expected_interest_minor)
        val newReturn = R.add(command.principal_minor, interest)

        if (dao.edit_deposit(old.id, old.revision, command.principal_minor,
                command.annual_rate_percent_e8, command.start_epoch_day, command.end_epoch_day,
                interest, openTarget, closeTarget, closed, now) != 1) {
            throw DomainException(ErrorCode.STALE_RECORD)
        }
        fault(TransactionPoint.AFTER_BUSINESS)
        cash.applyChanges(command.operation_id, listOf(
            old.open_cash_account_id to old.principal_minor,
            openTarget to -command.principal_minor,
            old.close_cash_account_id to -oldReturn,
            closeTarget to newReturn
        ), "TERM_EDIT", now)
        syncEntry(old, "TERM_OPEN", old.open_operation_id, old.open_cash_account_id,
            openTarget, -command.principal_minor, businessTime(command.start_epoch_day), now)
        if (closed) syncEntry(old, "TERM_CLOSE", requireNotNull(old.close_operation_id),
            old.close_cash_account_id, closeTarget, newReturn, requireNotNull(old.closed_at_ms), now)
        return OperationResult("TERM_DEPOSIT", old.id)
    }

    private suspend fun syncEntry(
        deposit: DepositEntity,
        kind: String,
        originalOperation: String,
        oldTarget: Long?,
        newTarget: Long?,
        newDelta: Long,
        occurred: Long,
        now: Long
    ) {
        val entry = db.cash().source_entry(kind, deposit.id)
        if (oldTarget != null && (entry == null || entry.is_deleted || entry.cash_account_id != oldTarget)) {
            throw IllegalStateException("Inconsistent deposit cash entry")
        }
        if (oldTarget == null && newTarget == null) return
        if (entry == null) {
            cash.entry(originalOperation, requireNotNull(newTarget), kind, deposit.id, newDelta, occurred, now)
            return
        }
        val targetId = newTarget ?: entry.cash_account_id
        val target = db.cash().cashAccount(targetId) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
        if (db.cash().editEntry(entry.id, entry.revision, target.id, target.savings_account_id,
            target.currency_code, if (newTarget != null) newDelta else entry.delta_minor,
                occurred, entry.note, newTarget == null, now) != 1) {
            throw DomainException(ErrorCode.STALE_RECORD)
        }
    }

    private fun businessTime(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay)
        .atStartOfDay(clock.zone).toInstant().toEpochMilli()
}
