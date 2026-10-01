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
    private val positions = PositionCommandHandler(db, cash, instruments, fault)
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
            dao.insert_operation(OperationEntity(command.operation_id, kind, digest, null, null, now))
            val result = when (command) {
                is SaveAssetType -> OperationResult("ASSET_TYPE", types.save(command.typeId, command.name, now))
                is SaveAccount -> accounts.save(command, now)
                is SaveInstrument -> instruments.save(command, now)
                is SaveOpeningPosition -> positions.opening(command, now)
                is RecordAccountTrade -> positions.record(command, now)
                is SetCashBalance -> accounts.setBalance(command, now)
                is EditCashEntry -> accounts.editEntry(command, now)
                is OpenTermDeposit -> deposits.open(command, now)
                is CloseTermDeposit -> deposits.close(command, now)
                is EditTermDeposit -> deposits.edit(command, now)
                is CreateInvestment -> positions.legacyCreate(command, now)
                is SetOpeningInvestmentCost -> positions.cost(command, now)
                is RecordInvestmentTrade -> positions.record(command, now)
                is EditInvestmentTrade -> positions.edit(command, now)
                is DeleteInvestmentTrade -> positions.delete(command, now)
            }
            fault(TransactionPoint.BEFORE_RECEIPT)
            dao.complete_operation(command.operation_id, result.kind, result.id)
            result
        }
    }
}
