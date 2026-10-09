package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CashDao {
    @Query("SELECT MIN(occurred_at_ms) AS firstMs,MAX(occurred_at_ms) AS lastMs FROM cash_entries WHERE cash_account_id=:id AND is_deleted=0")
    fun monthBounds(id: Long): Flow<dev.valnook.domain.repository.LedgerBounds>

    @Query("SELECT COALESCE(SUM(revision),0) FROM cash_accounts WHERE savings_account_id=:accountId AND currency_code=:code")
    fun ledgerRevision(accountId: Long, code: String): Flow<Long>

    @Query("SELECT COALESCE((SELECT revision FROM cash_accounts WHERE id=:cashAccountId),0)")
    fun cashAccountRevision(cashAccountId: Long): Flow<Long>

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.savings_account_id=:accountId AND e.currency_code=:code AND e.is_deleted=0
        AND (:time IS NULL OR e.occurred_at_ms<:time OR (e.occurred_at_ms=:time AND e.id<:id))
        ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :size""")
    suspend fun ledgerPage(accountId: Long, code: String, time: Long?, id: Long?, size: Int): List<CashEntryWithSource>

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.cash_account_id=:cashAccountId AND e.is_deleted=0
        AND (:time IS NULL OR e.occurred_at_ms<:time OR (e.occurred_at_ms=:time AND e.id<:id))
        ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :size""")
    suspend fun cashAccountLedgerPage(cashAccountId: Long, time: Long?, id: Long?, size: Int): List<CashEntryWithSource>

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.cash_account_id=:cashAccountId AND e.is_deleted=0
        AND e.occurred_at_ms>=:startMs AND e.occurred_at_ms<:endMs
        AND (:time IS NULL OR e.occurred_at_ms<:time OR (e.occurred_at_ms=:time AND e.id<:id))
        ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :size""")
    suspend fun cashAccountMonthPage(cashAccountId: Long, startMs: Long, endMs: Long, time: Long?, id: Long?, size: Int): List<CashEntryWithSource>

    @Query("""SELECT c.*,p.account_id AS profile_account_id,p.credit_limit_minor,p.statement_day,
        p.due_rule_type,p.due_rule_value,p.limit_source_account_id FROM cash_accounts c
        LEFT JOIN credit_account_profiles p ON p.account_id=c.id
        WHERE c.savings_account_id=:accountId ORDER BY c.display_order,c.id""")
    fun cash(accountId: Long): Flow<List<BalanceAccountRow>>

    @Query("SELECT COALESCE(MAX(display_order),-1)+1 FROM cash_accounts WHERE savings_account_id=:accountId")
    suspend fun nextDisplayOrder(accountId: Long): Long

    @Query("SELECT id FROM cash_accounts WHERE savings_account_id=:accountId")
    suspend fun accountIds(accountId: Long): List<Long>

    @Query("UPDATE cash_accounts SET display_order=:order WHERE id=:id AND savings_account_id=:accountId")
    suspend fun setDisplayOrder(accountId: Long, id: Long, order: Long): Int

    @Query("SELECT * FROM cash_accounts WHERE savings_account_id=:accountId AND currency_code=:code ORDER BY id LIMIT 1")
    suspend fun cash_one(accountId: Long, code: String): CashEntity?

    @Query("SELECT * FROM cash_accounts WHERE id=:cashAccountId")
    suspend fun cashAccount(cashAccountId: Long): CashEntity?

    @Query("""SELECT c.*,p.account_id AS profile_account_id,p.credit_limit_minor,p.statement_day,
        p.due_rule_type,p.due_rule_value,p.limit_source_account_id FROM cash_accounts c
        LEFT JOIN credit_account_profiles p ON p.account_id=c.id WHERE c.id=:cashAccountId""")
    fun observeCashAccount(cashAccountId: Long): Flow<BalanceAccountRow?>

    @Query("""SELECT c.* FROM cash_accounts c LEFT JOIN credit_account_profiles p ON p.account_id=c.id
        WHERE c.savings_account_id=:accountId AND c.currency_code=:code AND p.account_id IS NULL ORDER BY c.name,c.id""")
    suspend fun cashCandidates(accountId: Long, code: String): List<CashEntity>

    @Insert
    suspend fun insert_cash(value: CashEntity): Long

    @Query("""UPDATE cash_accounts SET balance_minor=:balance,revision=:revision,updated_at_ms=:now
        WHERE id=:cashAccountId AND revision=:oldRevision""")
    suspend fun updateCashBalance(cashAccountId: Long, balance: Long, revision: Long, oldRevision: Long, now: Long): Int

    @Query("""UPDATE cash_accounts SET name=:name,note=:note,revision=revision+1,updated_at_ms=:now
        WHERE id=:cashAccountId AND revision=:oldRevision""")
    suspend fun updateCashMetadata(cashAccountId: Long, oldRevision: Long, name: String, note: String, now: Long): Int

    @Query("""UPDATE cash_accounts SET name=:name,note=:note,balance_minor=:balance,
        revision=revision+1,updated_at_ms=:now WHERE id=:cashAccountId AND revision=:oldRevision""")
    suspend fun updateCashAccount(cashAccountId: Long, oldRevision: Long, name: String, note: String,
        balance: Long, now: Long): Int

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.cash_account_id=:cashAccountId AND e.is_deleted=0
        ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :limit""")
    fun cashAccountEntries(cashAccountId: Long, limit: Int): Flow<List<CashEntryWithSource>>

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.savings_account_id=:accountId AND e.currency_code=:code AND e.is_deleted=0
        ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :limit""")
    fun cash_entries(accountId: Long, code: String, limit: Int): Flow<List<CashEntryWithSource>>

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.cash_account_id=:cashAccountId AND e.id=:entryId AND e.is_deleted=0""")
    fun observeCashAccountEntry(cashAccountId: Long, entryId: Long): Flow<CashEntryWithSource?>

    @Query("""SELECT e.*,t.investment_id,c.name AS cash_account_name FROM cash_entries e
        JOIN cash_accounts c ON c.id=e.cash_account_id
        LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id
        WHERE e.savings_account_id=:accountId AND e.id=:entryId AND e.is_deleted=0""")
    fun observe_cash_entry(accountId: Long, entryId: Long): Flow<CashEntryWithSource?>

    @Query("SELECT * FROM cash_entries WHERE id=:id")
    suspend fun cash_entry(id: Long): CashEntryEntity?

    @Query("SELECT * FROM cash_entries WHERE source_kind=:kind AND source_id=:sourceId")
    suspend fun source_entry(kind: String, sourceId: Long): CashEntryEntity?

    @Query("SELECT COUNT(*) FROM cash_entries WHERE cash_account_id=:cashAccountId")
    suspend fun cashEntryCount(cashAccountId: Long): Int

    @Query("""SELECT COUNT(*) FROM cash_entries WHERE cash_account_id=:cashAccountId AND
        (source_kind!='CASH_SET' OR delta_minor!=0 OR is_deleted!=0)""")
    suspend fun meaningfulCashEntryCount(cashAccountId: Long): Int

    @Query("SELECT COUNT(*) FROM cash_movements WHERE cash_account_id=:cashAccountId AND delta_minor!=0")
    suspend fun meaningfulMovementCount(cashAccountId: Long): Int

    @Query("SELECT COUNT(*) FROM term_deposits WHERE open_cash_account_id=:cashAccountId OR close_cash_account_id=:cashAccountId")
    suspend fun depositReferenceCount(cashAccountId: Long): Int

    @Query("SELECT COUNT(*) FROM investment_trades WHERE cash_account_id=:cashAccountId")
    suspend fun tradeReferenceCount(cashAccountId: Long): Int

    @Query("DELETE FROM cash_entries WHERE cash_account_id=:cashAccountId AND source_kind='CASH_SET' AND delta_minor=0")
    suspend fun deleteZeroInitializationEntries(cashAccountId: Long): Int

    @Query("DELETE FROM cash_movements WHERE cash_account_id=:cashAccountId AND delta_minor=0")
    suspend fun deleteZeroInitializationMovements(cashAccountId: Long): Int

    @Query("DELETE FROM cash_accounts WHERE id=:cashAccountId AND revision=:expectedRevision AND balance_minor=0")
    suspend fun deleteEmptyCashAccount(cashAccountId: Long, expectedRevision: Long): Int

    @Query("UPDATE cash_accounts SET include_in_available_cash=:available, show_on_accounts_page=:visible WHERE id=:id")
    suspend fun setPresentation(id: Long, available: Boolean, visible: Boolean)

    @Insert
    suspend fun insert_entry(value: CashEntryEntity): Long

    @Query("""UPDATE cash_entries SET cash_account_id=:cashAccountId,savings_account_id=:accountId,currency_code=:code,
        delta_minor=:delta,occurred_at_ms=:occurred,note=:note,is_deleted=:deleted,revision=revision+1,updated_at_ms=:now
        WHERE id=:id AND revision=:oldRevision""")
    suspend fun editEntry(id: Long, oldRevision: Long, cashAccountId: Long, accountId: Long, code: String,
        delta: Long, occurred: Long, note: String, deleted: Boolean, now: Long): Int

    @Insert
    suspend fun insert_movement(value: MovementEntity): Long
}

data class BalanceAccountRow(
    @androidx.room.Embedded val account: CashEntity,
    val profile_account_id: Long?,
    val credit_limit_minor: Long?,
    val statement_day: Int?,
    val due_rule_type: String?,
    val due_rule_value: Int?,
    val limit_source_account_id: Long?
)
