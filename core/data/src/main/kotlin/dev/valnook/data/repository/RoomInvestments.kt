package dev.valnook.data.repository

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import androidx.room.withTransaction
import java.time.Clock
import java.text.Normalizer
import java.util.Locale
import dev.valnook.domain.calculation.InvestmentProfitCalculator

private fun InvestmentWithType.to_model():Investment {
    val i=asset
    return Investment(i.id,i.savings_account_id,i.asset_type_id,type_name,i.name,i.symbol,Currency.of(i.currency_code),
        i.opening_quantity_e8,i.holding_quantity_e8,i.current_price_e8,i.price_updated_at_ms,
        i.opening_cost_price_e8,i.revision,i.last_activity_at_ms)
}
private fun TradeEntity.to_model()=Trade(id,investment_id,Direction.valueOf(direction),quantity_e8,execution_price_e8,
    amount_minor,Currency.of(currency_code),cash_linked,occurred_at_ms,revision)

class RoomInvestments(private val db: ValnookDatabase, private val clock: Clock) : InvestmentRepository {
    private val dao = db.ledger()
    override suspend fun get_trade(id: Long): Trade? = dao.trade(id)?.takeUnless{it.is_deleted}?.let {
        Trade(it.id,it.investment_id,Direction.valueOf(it.direction),it.quantity_e8,it.execution_price_e8,
            it.amount_minor,Currency.of(it.currency_code),it.cash_linked,it.occurred_at_ms,it.revision)
    }
    override fun observe_investment(id: Long) = dao.investment_detail(id).map { it?.to_model() }
    override fun observe_investments(account_id: Long, limit: Int, section:InvestmentSection) =
        db.investment_reads().investments(account_id,section.name,limit.coerceIn(1,10000)).map { rows->rows.map{it.to_model()} }
    override fun observe_profit(id:Long)=db.investment_reads().profit_snapshot(id).map { snapshot->snapshot?.let {
        InvestmentProfitCalculator.calculate(it.investment.to_model(),it.trades.filterNot{trade->trade.is_deleted}.map{trade->trade.to_model()})
    } }.flowOn(Dispatchers.Default)
    override fun observe_types() = dao.types().map { rows -> rows.map { AssetType(it.id,it.name) } }
    override fun observe_trade_revision(investment_id: Long) = dao.trade_revision(investment_id)
    override suspend fun trade_page(investment_id: Long,cursor: TradeCursor?,limit: Int) =
        (if(cursor==null) dao.first_trades(investment_id,limit.coerceIn(1,100)) else
            dao.next_trades(investment_id,cursor.occurred_at_ms,cursor.id,limit.coerceIn(1,100))).map {
            Trade(it.id,it.investment_id,Direction.valueOf(it.direction),it.quantity_e8,it.execution_price_e8,
                it.amount_minor,Currency.of(it.currency_code),it.cash_linked,it.occurred_at_ms,it.revision) }
    override suspend fun save_type(id: Long?,name: String): Long = db.withTransaction {
        val label = valid_name(name)
        val normalized = Normalizer.normalize(label,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("\\s+")," ")
        val existing = dao.type_by_name(normalized)
        if(existing != null && existing != id) throw DomainException(ErrorCode.DUPLICATE_TYPE)
        if(id==null) dao.insert_type(TypeEntity(name=label,normalized_name=normalized,created_at_ms=clock.millis(),updated_at_ms=clock.millis()))
        else { if(dao.edit_type(id,label,normalized,clock.millis())!=1) throw DomainException(ErrorCode.NOT_FOUND); id }
    }
    override suspend fun edit_investment(id: Long,name: String,symbol: String,type_id: Long) = db.withTransaction {
        if(dao.type(type_id)==null) throw DomainException(ErrorCode.NOT_FOUND)
        if(symbol.length>100) throw DomainException(ErrorCode.FORMAT)
        if(dao.edit_investment(id,valid_name(name),symbol.trim(),type_id,clock.millis())!=1) throw DomainException(ErrorCode.NOT_FOUND)
    }
    override suspend fun update_price(id: Long,price_e8: Long) = db.withTransaction {
        DecimalRules.check_nonnegative(price_e8)
        if(dao.update_price(id,price_e8,clock.millis())!=1) throw DomainException(ErrorCode.NOT_FOUND)
    }
}
