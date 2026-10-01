package dev.valnook.data.transaction

import dev.valnook.data.database.*
import dev.valnook.data.repository.toModel
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*

internal class PositionCommandHandler(private val db: ValnookDatabase, private val cash: CashWriter,
    private val instruments: InstrumentCommandHandler, private val fault: (TransactionPoint) -> Unit) {
    private val positions = db.positions()
    private val trades = db.trades()

    suspend fun opening(command: SaveOpeningPosition, now: Long): OperationResult {
        cash.requireAccount(command.accountId)
        val instrument = db.instruments().instrument(command.instrumentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        cash.currency(instrument.currency_code)
        if (positions.position(command.accountId, command.instrumentId) != null) throw DomainException(ErrorCode.OPERATION_CONFLICT)
        R.check_nonnegative(command.quantityE8)
        command.costPriceE8?.let { R.check_nonnegative(it, true) }
        val cost = if (command.quantityE8 == 0L) "0" else command.costPriceE8?.let {
            java.math.BigDecimal.valueOf(R.amount(command.quantityE8, it, Currency.of(instrument.currency_code)),
                Currency.of(instrument.currency_code).fraction_digits).stripTrailingZeros().toPlainString()
        }
        val id = positions.insert_investment(InvestmentEntity(savings_account_id = command.accountId,
            instrument_id = command.instrumentId, opening_quantity_e8 = command.quantityE8,
            holding_quantity_e8 = command.quantityE8, revision = 1, created_at_ms = now, updated_at_ms = now,
            opening_cost_price_e8 = command.costPriceE8, opening_at_ms = command.occurredAtMs,
            remaining_cost = cost, realized_profit = "0", chronology_valid = true,
            algorithm_version = InvestmentProfitCalculator.ALGORITHM_VERSION,
            position_state = if (command.quantityE8 > 0) "HOLDING" else "PENDING", last_activity_at_ms = command.occurredAtMs))
        if (command.quantityE8 > 0) db.instruments().lockCurrency(command.instrumentId)
        fault(TransactionPoint.AFTER_BUSINESS)
        fault(TransactionPoint.AFTER_COST)
        return OperationResult("INVESTMENT", id)
    }

    // Existing command fixtures retain their identity; normal UI creates instruments globally.
    suspend fun legacyCreate(command: CreateInvestment, now: Long): OperationResult {
        if (command.opening_quantity_e8 > 0 && command.opening_cost_price_e8 == null) throw DomainException(ErrorCode.FORMAT)
        if (command.current_price_e8 % 1000 != 0L) throw DomainException(ErrorCode.PRECISION)
        val instrument = instruments.save(SaveInstrument(command.operation_id, null, null, command.name,
            command.symbol, command.type_id, command.currency_code, command.current_price_e8 / 1000), now)
        return opening(SaveOpeningPosition(command.operation_id, command.account_id, instrument.id,
            command.opening_quantity_e8, command.opening_cost_price_e8, Long.MIN_VALUE), now)
    }

    suspend fun record(command: RecordAccountTrade, now: Long): OperationResult {
        cash.requireAccount(command.accountId)
        val position = positions.position(command.accountId, command.instrumentId)?.id ?: opening(
            SaveOpeningPosition(command.operation_id, command.accountId, command.instrumentId, 0, null, command.occurredAtMs), now).id
        return record(RecordInvestmentTrade(command.operation_id, position, command.direction, command.quantityE8,
            command.executionPriceE8, command.occurredAtMs, command.cashLinked), now)
    }

    suspend fun record(command: RecordInvestmentTrade, now: Long): OperationResult {
        val position = positions.investment(command.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val amount = R.amount(command.quantity_e8, command.execution_price_e8, cash.currency(instrument.currency_code), true)
        if (command.direction == Direction.SELL && command.quantity_e8 > position.holding_quantity_e8)
            throw DomainException(ErrorCode.INSUFFICIENT_HOLDING)
        val id = trades.insert_trade(TradeEntity(investment_id = position.id, operation_id = command.operation_id,
            direction = command.direction.name, quantity_e8 = command.quantity_e8, execution_price_e8 = command.execution_price_e8,
            amount_minor = amount, currency_code = instrument.currency_code, cash_linked = command.cash_linked,
            occurred_at_ms = command.occurred_at_ms, created_at_ms = now, updated_at_ms = now))
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        db.instruments().lockCurrency(position.instrument_id)
        if (command.cash_linked) {
            val delta = impact(command.direction, amount)
            cash.change(command.operation_id, position.savings_account_id, instrument.currency_code, delta, command.direction.name, now)
            cash.entry(command.operation_id, position.savings_account_id, instrument.currency_code, "TRADE", id, delta, command.occurred_at_ms, now)
        }
        return OperationResult("INVESTMENT_TRADE", id)
    }

    suspend fun cost(command: SetOpeningInvestmentCost, now: Long): OperationResult {
        val position = positions.investment(command.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (position.revision != command.expected_revision) throw DomainException(ErrorCode.STALE_RECORD)
        if (position.opening_quantity_e8 == 0L) throw DomainException(ErrorCode.FORMAT)
        R.check_nonnegative(command.price_e8, true)
        if (positions.update_opening_cost(position.id, command.price_e8, position.revision) != 1)
            throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position.copy(opening_cost_price_e8 = command.price_e8), now)
        return OperationResult("INVESTMENT", position.id)
    }

    suspend fun edit(command: EditInvestmentTrade, now: Long): OperationResult {
        val old = active(command.trade_id, command.expected_revision)
        val position = positions.investment(old.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val amount = R.amount(command.quantity_e8, command.execution_price_e8, cash.currency(instrument.currency_code), true)
        if (trades.edit_trade(old.id, old.revision, command.direction.name, command.quantity_e8, command.execution_price_e8,
                amount, command.cash_linked, command.occurred_at_ms, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        syncCash(command.operation_id, old, position, command.direction, amount, command.cash_linked,
            command.occurred_at_ms, now, "TRADE_EDIT")
        return OperationResult("INVESTMENT_TRADE", old.id)
    }

    suspend fun delete(command: DeleteInvestmentTrade, now: Long): OperationResult {
        val old = active(command.trade_id, command.expected_revision)
        val position = positions.investment(old.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (trades.delete_trade(old.id, old.revision, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        syncCash(command.operation_id, old, position, Direction.valueOf(old.direction), old.amount_minor,
            false, old.occurred_at_ms, now, "TRADE_DELETE")
        return OperationResult("INVESTMENT_TRADE", old.id)
    }

    private suspend fun active(id: Long, expectedRevision: Long): TradeEntity {
        val trade = trades.trade(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (trade.is_deleted || trade.revision != expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        R.add(trade.revision, 1)
        return trade
    }

    private suspend fun rebuild(position: InvestmentEntity, now: Long) {
        val projection = positions.positionSnapshot(position.id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val history = trades.replayTrades(position.id).map { it.toModel() }
        var holding = position.opening_quantity_e8
        for (trade in history) {
            holding = R.add(holding, if (trade.direction == Direction.BUY) trade.quantity_e8 else -trade.quantity_e8)
            if (holding < 0) throw DomainException(ErrorCode.HISTORY_CONFLICT)
        }
        val result = InvestmentProfitCalculator.calculate(projection.toModel().copy(holding_quantity_e8 = holding), history)
        if (!result.chronology_valid) throw DomainException(ErrorCode.HISTORY_CONFLICT)
        if (positions.saveCost(position.id, position.revision, holding,
                result.remainingCost?.stripTrailingZeros()?.toPlainString(), result.realized?.stripTrailingZeros()?.toPlainString(),
                true, InvestmentProfitCalculator.ALGORITHM_VERSION, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_COST)
    }

    private fun impact(direction: Direction, amount: Long): Long = if (direction == Direction.BUY) -amount else amount

    private suspend fun syncCash(operationId: String, old: TradeEntity, position: InvestmentEntity,
        direction: Direction, amount: Long, linked: Boolean, occurred: Long, now: Long, reason: String) {
        val oldImpact = if (old.cash_linked) impact(Direction.valueOf(old.direction), old.amount_minor) else 0
        val newImpact = if (linked) impact(direction, amount) else 0
        val entry = db.cash().source_entry("TRADE", old.id)
        if (old.cash_linked) check(entry != null && !entry.is_deleted && entry.delta_minor == oldImpact)
        if (!old.cash_linked && !linked) return
        // Only this record's difference affects cash; replay never repeats previous cash effects.
        cash.change(operationId, position.savings_account_id, old.currency_code,
            R.replace_contribution(0, oldImpact, newImpact), reason, now)
        if (entry == null) cash.entry(old.operation_id, position.savings_account_id, old.currency_code,
            "TRADE", old.id, newImpact, occurred, now)
        else if (db.cash().edit_entry(entry.id, entry.revision, if (linked) newImpact else entry.delta_minor,
                occurred, entry.note, !linked, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
    }
}
