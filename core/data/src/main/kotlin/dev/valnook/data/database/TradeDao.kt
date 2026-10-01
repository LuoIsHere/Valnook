package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TradeDao {
    @Query("SELECT * FROM investment_trades WHERE investment_id=:positionId AND is_deleted=0 ORDER BY occurred_at_ms,id")
    suspend fun replayTrades(positionId: Long): List<TradeEntity>
    @Query("SELECT * FROM investment_trades WHERE investment_id=:investment_id AND is_deleted=0 ORDER BY occurred_at_ms DESC,id DESC LIMIT :limit")
    suspend fun first_trades(investment_id: Long, limit: Int): List<TradeEntity>
    @Query("SELECT * FROM investment_trades WHERE investment_id=:investment_id AND is_deleted=0 AND (occurred_at_ms < :time OR (occurred_at_ms = :time AND id < :id)) ORDER BY occurred_at_ms DESC,id DESC LIMIT :limit")
    suspend fun next_trades(investment_id: Long, time: Long, id: Long, limit: Int): List<TradeEntity>
    @Insert suspend fun insert_trade(value: TradeEntity): Long
    @Query("SELECT * FROM investment_trades WHERE id=:id")
    suspend fun trade(id: Long): TradeEntity?
    @Query("SELECT t.* FROM investment_trades t INNER JOIN investments i ON i.id=t.investment_id WHERE i.savings_account_id=:account_id AND t.id=:id AND t.is_deleted=0")
    fun observe_trade(account_id: Long, id: Long): Flow<TradeEntity?>
    @Query("UPDATE investment_trades SET direction=:direction,quantity_e8=:quantity,execution_price_e8=:price,amount_minor=:amount,cash_linked=:cashAccountId IS NOT NULL,cash_account_id=:cashAccountId,occurred_at_ms=:occurred,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision AND is_deleted=0")
    suspend fun edit_trade(id: Long, old_revision: Long, direction: String, quantity: Long, price: Long, amount: Long,
        cashAccountId: Long?, occurred: Long, now: Long): Int
    @Query("UPDATE investment_trades SET is_deleted=1,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision AND is_deleted=0")
    suspend fun delete_trade(id: Long, old_revision: Long, now: Long): Int
}
