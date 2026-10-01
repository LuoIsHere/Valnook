package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
    @Query("SELECT COALESCE((SELECT revision FROM cash_balances WHERE savings_account_id=:accountId AND currency_code=:code),0)")
    fun ledgerRevision(accountId: Long, code: String): Flow<Long>
    @Query("SELECT e.*,t.investment_id FROM cash_entries e LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id WHERE e.savings_account_id=:accountId AND e.currency_code=:code AND e.is_deleted=0 AND (:time IS NULL OR e.occurred_at_ms<:time OR (e.occurred_at_ms=:time AND e.id<:id)) ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :size")
    suspend fun ledgerPage(accountId: Long, code: String, time: Long?, id: Long?, size: Int): List<CashEntryWithSource>
    @Query("SELECT savings_account_id,currency_code,balance_minor,revision,updated_at_ms FROM cash_balances WHERE savings_account_id=:account_id ORDER BY currency_code")
    fun cash(account_id: Long): Flow<List<CashEntity>>
    @Query("SELECT savings_account_id,currency_code,balance_minor,revision,updated_at_ms FROM cash_balances WHERE savings_account_id=:account_id AND currency_code=:code")
    suspend fun cash_one(account_id: Long, code: String): CashEntity?
    @Insert suspend fun insert_cash(value: CashEntity)
    @Query("UPDATE cash_balances SET balance_minor=:balance,revision=:revision,updated_at_ms=:now WHERE savings_account_id=:account_id AND currency_code=:code AND revision=:old_revision")
    suspend fun update_cash(account_id: Long, code: String, balance: Long, revision: Long, old_revision: Long, now: Long): Int
    @Query("SELECT e.id,e.original_operation_id,e.savings_account_id,e.currency_code,e.source_kind,e.source_id,e.delta_minor,e.occurred_at_ms,e.note,e.revision,e.is_deleted,e.created_at_ms,e.updated_at_ms,t.investment_id FROM cash_entries e LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id WHERE e.savings_account_id=:account_id AND e.currency_code=:code AND e.is_deleted=0 ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :limit")
    fun cash_entries(account_id: Long, code: String, limit: Int): Flow<List<CashEntryWithSource>>
    @Query("SELECT e.*,t.investment_id FROM cash_entries e LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id WHERE e.savings_account_id=:account_id AND e.id=:entry_id AND e.is_deleted=0")
    fun observe_cash_entry(account_id: Long, entry_id: Long): Flow<CashEntryWithSource?>
    @Query("SELECT id,original_operation_id,savings_account_id,currency_code,source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms FROM cash_entries WHERE id=:id")
    suspend fun cash_entry(id: Long): CashEntryEntity?
    @Query("SELECT id,original_operation_id,savings_account_id,currency_code,source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms FROM cash_entries WHERE source_kind=:kind AND source_id=:source_id")
    suspend fun source_entry(kind: String, source_id: Long): CashEntryEntity?
    @Insert suspend fun insert_entry(value: CashEntryEntity): Long
    @Query("UPDATE cash_entries SET delta_minor=:delta,occurred_at_ms=:occurred,note=:note,is_deleted=:deleted,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision")
    suspend fun edit_entry(id: Long, old_revision: Long, delta: Long, occurred: Long, note: String, deleted: Boolean, now: Long): Int
    @Insert suspend fun insert_movement(value: MovementEntity): Long
}
