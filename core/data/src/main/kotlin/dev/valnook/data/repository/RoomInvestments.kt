package dev.valnook.data.repository

import dev.valnook.data.database.*
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.map

class RoomInvestments(private val db: ValnookDatabase) : InvestmentRepository {
    override fun observe_trade(account_id: Long, id: Long) =
        db.trades().observe_trade(account_id, id).map { it?.toModel() }

    override suspend fun get_trade(id: Long): Trade? = db.trades().trade(id)?.takeUnless { it.is_deleted }?.toModel()
    override fun observe_investment(id: Long) = db.positions().investment_detail(id).map { it?.toModel() }
    override fun observe_investments(account_id: Long, limit: Int, section: InvestmentSection) =
        db.positions().accountPositions(account_id, section.name, limit.coerceIn(1, 10000))
            .map { rows -> rows.map { it.toModel() } }
    override fun observe_profit(id: Long) = observe_investment(id).map { it?.let(InvestmentProfitCalculator::fromReadModel) }
    override fun observe_types() = db.instruments().types().map { rows -> rows.map { AssetType(it.id, it.name) } }
    override fun observe_trade_revision(investment_id: Long) = db.positions().trade_revision(investment_id)
    override suspend fun trade_page(investment_id: Long, cursor: TradeCursor?, limit: Int): List<Trade> {
        val size = limit.coerceIn(1, 100)
        val rows = if (cursor == null) db.trades().first_trades(investment_id, size) else
            db.trades().next_trades(investment_id, cursor.occurred_at_ms, cursor.id, size)
        return rows.map { it.toModel() }
    }
}

class RoomInstruments(private val db: ValnookDatabase) : InstrumentRepository {
    override fun observeInstruments() = db.instruments().instruments().map { rows -> rows.map { it.toModel() } }
    override fun observeInstrument(id: Long) = db.instruments().observeInstrument(id).map { it?.toModel() }
}
