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

    suspend fun create(command: CreateInvestmentPosition, now: Long): OperationResult {
        cash.requireAccount(command.accountId)
        val instrument = db.instruments().instrument(command.instrumentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        cash.currency(instrument.currency_code)
        if (positions.position(command.accountId, command.instrumentId) != null) throw DomainException(ErrorCode.OPERATION_CONFLICT)
        val id = positions.insert_investment(InvestmentEntity(savings_account_id = command.accountId,
            instrument_id = command.instrumentId, holding_quantity_e8 = 0, revision = 1,
            created_at_ms = now, updated_at_ms = now, remaining_cost = "0", realized_profit = "0", chronology_valid = true,
            algorithm_version = InvestmentProfitCalculator.ALGORITHM_VERSION,
            position_state = "PENDING", last_activity_at_ms = now))
        db.instruments().lockCurrency(command.instrumentId)
        fault(TransactionPoint.AFTER_BUSINESS)
        fault(TransactionPoint.AFTER_COST)
        return OperationResult("INVESTMENT", id)
    }

    suspend fun record(command: RecordInvestmentTrade, now: Long): OperationResult {
        // Dividends have their own command; a trade never carries that direction.
        if (command.direction == Direction.DIVIDEND) throw DomainException(ErrorCode.FORMAT)
        val position = if (command.investment_id != 0L) {
            positions.investment(command.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        } else {
            val accountId = command.accountId ?: throw DomainException(ErrorCode.NOT_FOUND)
            val instrumentId = command.instrumentId ?: throw DomainException(ErrorCode.NOT_FOUND)
            cash.requireAccount(accountId)
            positions.position(accountId, instrumentId) ?: run {
                if (command.direction != Direction.BUY) throw DomainException(ErrorCode.INSUFFICIENT_HOLDING)
                val result = create(CreateInvestmentPosition(command.operation_id, accountId, instrumentId), now)
                requireNotNull(positions.investment(result.id))
            }
        }
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
        if (command.setAsCurrentPrice) setCurrentPrice(position.instrument_id, command.execution_price_e8, command.occurred_at_ms, now)
        return OperationResult("INVESTMENT_TRADE", id)
    }

    /**
     * The trade's price becomes the current one, dated at the trade, as a separate price record. An older trade would
     * put an old price over a newer update, so it is refused as stale.
     */
    private suspend fun setCurrentPrice(instrumentId: Long, executionPriceE8: Long, occurredAtMs: Long, now: Long) {
        val instrument = db.instruments().instrument(instrumentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (occurredAtMs < instrument.price_updated_at_ms) throw DomainException(ErrorCode.STALE_RECORD)
        val priceE5 = java.math.BigDecimal.valueOf(executionPriceE8, 8).setScale(5, java.math.RoundingMode.HALF_UP)
            .unscaledValue().longValueExact()
        if (db.instruments().updateCurrentPriceFromHistory(instrumentId, priceE5, occurredAtMs, now) != 1)
            throw DomainException(ErrorCode.STALE_RECORD)
        db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrumentId, price_e5 = priceE5,
            currency_code = instrument.currency_code, effective_at_ms = occurredAtMs, created_at_ms = now))
    }

    suspend fun edit(command: EditInvestmentTrade, now: Long): OperationResult {
        val old = active(command.trade_id, command.expected_revision)
        if (command.direction == Direction.DIVIDEND || old.direction == Direction.DIVIDEND.name) throw DomainException(ErrorCode.FORMAT)
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

    /** Requires the position to exist and to have bought before the date; cash, when linked, rises by the amount. */
    suspend fun recordDividend(command: RecordDividend, now: Long): OperationResult {
        val position = positions.investment(command.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        cash.currency(instrument.currency_code)
        R.check_nonnegative(command.amount_minor, positive = true)
        val cashAccountId = cash.resolveLink(position.savings_account_id, instrument.currency_code,
            command.cash_linked, command.cashAccountId)
        val id = trades.insert_trade(TradeEntity(investment_id = position.id, operation_id = command.operation_id,
            direction = Direction.DIVIDEND.name, quantity_e8 = 0, execution_price_e8 = 0,
            amount_minor = command.amount_minor, currency_code = instrument.currency_code, cash_linked = cashAccountId != null,
            cash_account_id = cashAccountId, occurred_at_ms = command.occurred_at_ms, created_at_ms = now,
            updated_at_ms = now, fee_minor = 0))
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        if (cashAccountId != null) {
            cash.change(command.operation_id, cashAccountId, command.amount_minor, Direction.DIVIDEND.name, now)
            cash.entry(command.operation_id, cashAccountId, CashSource.DIVIDEND.name, id, command.amount_minor,
                command.occurred_at_ms, now)
        }
        return OperationResult("INVESTMENT_TRADE", id)
    }

    suspend fun editDividend(command: EditDividend, now: Long): OperationResult {
        val old = active(command.trade_id, command.expected_revision)
        if (old.direction != Direction.DIVIDEND.name) throw DomainException(ErrorCode.FORMAT)
        val position = positions.investment(old.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        R.check_nonnegative(command.amount_minor, positive = true)
        val cashAccountId = cash.resolveLink(position.savings_account_id, instrument.currency_code,
            command.cash_linked, command.cashAccountId)
        if (trades.edit_trade(old.id, old.revision, Direction.DIVIDEND.name, 0, 0, command.amount_minor, 0,
                cashAccountId, command.occurred_at_ms, now) != 1) throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        rebuild(position, now)
        syncCash(command.operation_id, old, position, Direction.DIVIDEND, command.amount_minor, 0, cashAccountId,
            command.occurred_at_ms, now, "DIVIDEND_EDIT")
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
        var holding = 0L
        for (trade in history) {
            holding = R.add(holding, when (trade.direction) {
                Direction.BUY -> trade.quantity_e8
                Direction.SELL -> -trade.quantity_e8
                Direction.DIVIDEND -> 0
            })
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
        Direction.DIVIDEND -> amount
    }

    /** The cash entry kind a record of this direction keeps. */
    private fun sourceKind(direction: Direction): String =
        if (direction == Direction.DIVIDEND) CashSource.DIVIDEND.name else CashSource.TRADE.name

    private suspend fun syncCash(operationId: String, old: TradeEntity, position: InvestmentEntity,
        direction: Direction, amount: Long, fee: Long, cashAccountId: Long?, occurred: Long, now: Long, reason: String) {
        val oldImpact = if (old.cash_account_id != null)
            impact(Direction.valueOf(old.direction), old.amount_minor, old.fee_minor) else 0
        val newImpact = if (cashAccountId != null) impact(direction, amount, fee) else 0
        val kind = sourceKind(Direction.valueOf(old.direction))
        val entry = db.cash().source_entry(kind, old.id)
        // A cash entry that no longer matches its trade is a source record problem the screen can name.
        if (old.cash_account_id != null && (entry == null || entry.is_deleted || entry.delta_minor != oldImpact))
            throw DomainException(ErrorCode.SOURCE_RECORD)
        if (old.cash_account_id == null && cashAccountId == null) return
        // Only this record's difference affects cash; replay never repeats previous cash effects.
        cash.applyPlan(operationId, old.cash_account_id, oldImpact, cashAccountId, newImpact, reason, now)
        cash.syncSourceEntry(entry, old.operation_id, kind, old.id, cashAccountId, newImpact, occurred, now)
    }
}
