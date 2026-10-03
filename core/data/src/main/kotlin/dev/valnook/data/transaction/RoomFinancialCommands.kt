package dev.valnook.data.transaction

import androidx.room.withTransaction
import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import java.time.Clock
import java.util.UUID

enum class TransactionPoint { AFTER_BUSINESS, AFTER_COST, AFTER_CASH, BEFORE_RECEIPT }

/** All handlers run inside this single transaction. Fault hooks are no-op in production. */
class RoomFinancialCommands(private val db: ValnookDatabase, private val clock: Clock,
    private val fault: (TransactionPoint) -> Unit = {}) : FinancialCommands {
    private val cash = CashWriter(db, fault)
    private val accounts = AccountCommandHandler(db, cash, fault)
    private val deposits = DepositCommandHandler(db, cash, clock, fault)
    private val instruments = InstrumentCommandHandler(db, cash, fault)
    private val positions = PositionCommandHandler(db, cash, fault)
    private val types = AssetTypeWriter(db.instruments())

    override suspend fun operationResult(operationId: String): OperationResult? = db.operations().operation(operationId)?.let {
        if (it.result_kind == null || it.result_id == null) null else OperationResult(it.result_kind, it.result_id)
    }

    override suspend fun execute(command: FinancialCommand): OperationResult {
        try { UUID.fromString(command.operation_id) } catch (_: IllegalArgumentException) { throw DomainException(ErrorCode.FORMAT) }
        val (kind, digest) = CommandFingerprint.fingerprint(command)
        return db.withTransaction {
            val dao = db.operations()
            val old = dao.operation(command.operation_id)
            if (old != null) {
                if (old.kind != kind || old.request_fingerprint != digest) throw DomainException(ErrorCode.OPERATION_CONFLICT)
                return@withTransaction OperationResult(requireNotNull(old.result_kind), requireNotNull(old.result_id))
            }
            val now = clock.millis()
            val invalidatedDay = earliestAffectedDay(command, now)
            dao.insert_operation(OperationEntity(command.operation_id, kind, digest, null, null, now))
            val result = when (command) {
                is SaveAssetType -> OperationResult("ASSET_TYPE", types.save(command.typeId, command.name, now))
                is SaveAccount -> accounts.save(command, now)
                is SaveInstrument -> instruments.save(command, now)
                is EditInstrumentPrice -> instruments.editPrice(command, now)
                is CreateInvestmentPosition -> positions.create(command, now)
                is SetCashBalance -> accounts.setBalance(command, now)
                is EditCashEntry -> accounts.editEntry(command, now)
                is OpenTermDeposit -> deposits.open(command, now)
                is CloseTermDeposit -> deposits.close(command, now)
                is EditTermDeposit -> deposits.edit(command, now)
                is RecordInvestmentTrade -> positions.record(command, now)
                is EditInvestmentTrade -> positions.edit(command, now)
                is DeleteInvestmentTrade -> positions.delete(command, now)
            }
            fault(TransactionPoint.BEFORE_RECEIPT)
            invalidatedDay?.let { db.statistics().invalidate(it) }
            dao.complete_operation(command.operation_id, result.kind, result.id)
            result
        }
    }

    private suspend fun earliestAffectedDay(command: FinancialCommand, now: Long): Long? {
        fun day(milliseconds: Long): Long = java.time.Instant.ofEpochMilli(milliseconds)
            .atZone(clock.zone).toLocalDate().toEpochDay()
        val baseline = db.statistics().state()?.baseline_at_ms?.let(::day) ?: day(now)
        return when (command) {
            is SaveAssetType, is CreateInvestmentPosition -> null
            is SaveInstrument -> {
                val old = command.instrumentId?.let { db.instruments().instrument(it) }
                if (old == null || old.current_price_e5 != command.currentPriceE5 ||
                    old.currency_code != command.currencyCode) day(now) else null
            }
            is EditInstrumentPrice -> {
                val old = db.statistics().price(command.priceRecordId)
                    ?: throw DomainException(ErrorCode.NOT_FOUND)
                minOf(day(old.effective_at_ms), day(command.effectiveAtMs)).coerceAtLeast(baseline)
            }
            is SaveAccount -> if (cashBalanceChanges(command)) day(now) else null
            is RecordInvestmentTrade -> day(command.occurred_at_ms).coerceAtLeast(baseline)
            is EditInvestmentTrade -> {
                val old = db.trades().trade(command.trade_id)?.occurred_at_ms?.let(::day) ?: day(command.occurred_at_ms)
                minOf(old, day(command.occurred_at_ms)).coerceAtLeast(baseline)
            }
            is DeleteInvestmentTrade -> (db.trades().trade(command.trade_id)?.occurred_at_ms?.let(::day)
                ?: baseline).coerceAtLeast(baseline)
            is EditCashEntry -> {
                val old = db.cash().cash_entry(command.entry_id)?.occurred_at_ms?.let(::day) ?: day(command.occurred_at_ms)
                minOf(old, day(command.occurred_at_ms)).coerceAtLeast(baseline)
            }
            is SetCashBalance, is CloseTermDeposit -> day(now)
            is OpenTermDeposit -> command.start_epoch_day.coerceAtLeast(baseline)
            is EditTermDeposit -> {
                val old = db.deposits().deposit(command.deposit_id)
                    ?: throw DomainException(ErrorCode.NOT_FOUND)
                minOf(old.start_epoch_day, command.start_epoch_day).coerceAtLeast(baseline)
            }
        }
    }

    private suspend fun cashBalanceChanges(command: SaveAccount): Boolean {
        if (command.accountId == null) return command.cashChanges.isNotEmpty()
        for (row in command.cashChanges) {
            val old = row.cashAccountId?.let { db.cash().cashAccount(it) }
            if (old == null || old.balance_minor != row.balanceMinor) return true
        }
        return false
    }
}
