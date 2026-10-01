package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.*
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.map
import dev.valnook.data.transaction.AssetTypeWriter
import java.time.Clock

class RoomInvestments(private val db: ValnookDatabase, private val clock: Clock) : InvestmentRepository {
    override fun observe_trade(account_id: Long, id: Long) =
        db.trades().observe_trade(account_id, id).map { it?.toModel() }

    override suspend fun get_trade(id: Long): Trade? = db.trades().trade(id)?.takeUnless { it.is_deleted }?.toModel()
    override fun observe_investment(id: Long) = db.positions().investment_detail(id).map { it?.toModel() }
    override fun observe_investments(account_id: Long, limit: Int, section: InvestmentSection) =
        db.positions().accountPositions(account_id).map { rows ->
            rows.filter {
                when (section) {
                    InvestmentSection.HOLDING -> it.asset.holding_quantity_e8 > 0
                    InvestmentSection.ALL -> true
                    InvestmentSection.CLOSED -> it.asset.position_state == "CLOSED"
                    InvestmentSection.PENDING -> it.asset.position_state == "PENDING"
                }
            }.map { it.toModel() }
        }
    override fun observe_profit(id: Long) = observe_investment(id).map { it?.let(InvestmentProfitCalculator::fromReadModel) }
    override fun observe_types() = db.instruments().types().map { rows -> rows.map { AssetType(it.id, it.name) } }
    override fun observe_trade_revision(investment_id: Long) = db.positions().trade_revision(investment_id)
    override suspend fun trade_page(investment_id: Long, cursor: TradeCursor?, limit: Int): List<Trade> {
        val size = limit.coerceIn(1, 100)
        val rows = if (cursor == null) db.trades().first_trades(investment_id, size) else
            db.trades().next_trades(investment_id, cursor.occurred_at_ms, cursor.id, size)
        return rows.map { it.toModel() }
    }
    override suspend fun save_type(id: Long?, name: String): Long = db.withTransaction {
        AssetTypeWriter(db.instruments()).save(id, name, clock.millis())
    }
    override suspend fun edit_investment(id: Long, name: String, symbol: String, type_id: Long) {
        db.withTransaction {
            val position = db.positions().investment(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            if (db.instruments().type(type_id) == null) throw DomainException(ErrorCode.NOT_FOUND)
            if (symbol.length > 100) throw DomainException(ErrorCode.FORMAT)
            db.instruments().updateInstrument(instrument.copy(name = valid_name(name), symbol = symbol.trim(),
                asset_type_id = type_id, revision = DecimalRules.add(instrument.revision, 1), updated_at_ms = clock.millis()))
        }
    }
    override suspend fun update_price(id: Long, price_e8: Long) {
        db.withTransaction {
            DecimalRules.check_nonnegative(price_e8)
            if (price_e8 % 1000 != 0L) throw DomainException(ErrorCode.PRECISION)
            val position = db.positions().investment(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            val instrument = db.instruments().instrument(position.instrument_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
            db.instruments().updateInstrument(instrument.copy(current_price_e5 = price_e8 / 1000,
                price_updated_at_ms = clock.millis(), updated_at_ms = clock.millis(),
                revision = DecimalRules.add(instrument.revision, 1)))
        }
    }
}

class RoomInstruments(private val db: ValnookDatabase, private val commands: FinancialCommands) : InstrumentRepository {
    override fun observeInstruments() = db.instruments().instruments().map { rows -> rows.map { it.toModel() } }
    override fun observeInstrument(id: Long) = db.instruments().observeInstrument(id).map { it?.toModel() }
    override suspend fun saveInstrument(command: SaveInstrument) = commands.execute(command)
}
