package dev.valnook.data.transaction

import dev.valnook.data.database.*
import dev.valnook.data.repository.toModel
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*

internal class PositionCommandHandler(private val db: ValnookDatabase, private val cash: CashWriter,
    private val fault: (TransactionPoint) -> Unit) {
    private val positions = db.positions()
    private val trades = db.trades()

    suspend fun opening(command: SaveOpeningPosition, now: Long): OperationResult {
        cash.requireAccount(command.accountId)
        val instrument = db.instruments().instrument(command.instrumentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        cash.currency(instrument.currency_code)
        if (positions.position(command.accountId, command.instrumentId) != null) throw DomainException(ErrorCode.OPERATION_CONFLICT)
        R.check_nonnegative(command.quantityE8)
        if (command.quantityE8 > 0 && command.costPriceE8 == null) throw DomainException(ErrorCode.FORMAT)
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

    suspend fun record(command: RecordAccountTrade, now: Long): OperationResult {
        cash.requireAccount(command.accountId)
        val position = positions.position(command.accountId, command.instrumentId)?.id ?: opening(
            SaveOpeningPosition(command.operation_id, command.accountId, command.instrumentId, 0, null, command.occurredAtMs), now).id
        return record(RecordInvestmentTrade(command.operation_id, position, command.direction, command.quantityE8,
            command.executionPriceE8, command.occurredAtMs, command.cashLinked, command.cashAccountId,
            command.feeMinor), now)
    }

    suspend fun record(command: RecordInvestmentTrade, now: Long): OperationResult {
        val position = positions.investment(command.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val amount = R.amount(command.quantity_e8, command.execution_price_e8, cash.currency(instrument.currency_code), true)
        R.check_nonnegative(command.fee_minor)
        val cashAccountId = cash.resolveLink(position.savings_account_id, instrument.currency_code,
            command.cash_linked, command.cashAccountId)
        if (command.direction == Direction.SELL && command.quantity_e8 > position.holding_quantity_e8)
            throw DomainException(ErrorCode.INSUFFICIENT_HOLDING)
        val id = trades.insert_trade(TradeEntity(investment_id = position.id, operation_id = command.operation_id,
            direction = command.direction.name, quantity_e8 = command.quantity_e8, execution_price_e8 = command.execution_price_e8,
            amount_minor = amount, currency_code = instrument.currency_code, cash_linked = cashAccountId != null,
            cash_account_id = cashAccountId,
            occurred_at_ms = command.occurred_at_ms, created_at_ms = now, updated_at_ms = now,
            fee_minor = command.fee_minor))
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        db.instruments().lockTradeIdentity(position.instrument_id)
        if (cashAccountId != null) {
            val delta = impact(command.direction, amount, command.fee_minor)
            cash.change(command.operation_id, cashAccountId, delta, command.direction.name, now)
            cash.entry(command.operation_id, cashAccountId, "TRADE", id, delta, command.occurred_at_ms, now)
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
        R.check_nonnegative(command.fee_minor)
        val cashAccountId = cash.resolveLink(position.savings_account_id, instrument.currency_code,
            command.cash_linked, command.cashAccountId)
        if (trades.edit_trade(old.id, old.revision, command.direction.name, command.quantity_e8, command.execution_price_e8,
                amount, command.fee_minor, cashAccountId, command.occurred_at_ms, now) != 1)
            throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        syncCash(command.operation_id, old, position, command.direction, amount, command.fee_minor, cashAccountId,
            command.occurred_at_ms, now, "TRADE_EDIT")
        return OperationResult("INVESTMENT_TRADE", old.id)
    }

    suspend fun delete(command: DeleteInvestmentTrade, now: Long): OperationResult {
        val old = active(command.trade_id, command.expected_revision)
        val position = positions.investment(old.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (trades.delete_trade(old.id, old.revision, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        syncCash(command.operation_id, old, position, Direction.valueOf(old.direction), old.amount_minor, 0,
            null, old.occurred_at_ms, now, "TRADE_DELETE")
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

    private fun impact(direction: Direction, amount: Long, fee: Long): Long = when (direction) {
        Direction.BUY -> -R.add(amount, fee)
        Direction.SELL -> R.replace_contribution(amount, fee, 0)
    }

    private suspend fun syncCash(operationId: String, old: TradeEntity, position: InvestmentEntity,
        direction: Direction, amount: Long, fee: Long, cashAccountId: Long?, occurred: Long, now: Long, reason: String) {
        val oldImpact = if (old.cash_account_id != null)
            impact(Direction.valueOf(old.direction), old.amount_minor, old.fee_minor) else 0
        val newImpact = if (cashAccountId != null) impact(direction, amount, fee) else 0
        val entry = db.cash().source_entry("TRADE", old.id)
        if (old.cash_account_id != null) check(entry != null && !entry.is_deleted && entry.delta_minor == oldImpact)
        if (old.cash_account_id == null && cashAccountId == null) return
        // Only this record's difference affects cash; replay never repeats previous cash effects.
        cash.applyPlan(operationId, old.cash_account_id, oldImpact, cashAccountId, newImpact, reason, now)
        if (entry == null) cash.entry(old.operation_id, requireNotNull(cashAccountId),
            "TRADE", old.id, newImpact, occurred, now)
        else {
            val targetId = cashAccountId ?: entry.cash_account_id
            val target = db.cash().cashAccount(targetId) ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT)
            if (db.cash().editEntry(entry.id, entry.revision, target.id, target.savings_account_id,
                    target.currency_code, if (cashAccountId != null) newImpact else entry.delta_minor,
                    occurred, entry.note, cashAccountId == null, now) != 1) {
                throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
    }
}
