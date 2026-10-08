package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DepositDao {
    @Query("""SELECT * FROM term_deposits WHERE savings_account_id=:accountId AND status='CLOSED'
        AND closed_at_ms>=:startMs AND closed_at_ms<:endMs
        AND (:time IS NULL OR closed_at_ms<:time OR (closed_at_ms=:time AND id<:id))
        ORDER BY closed_at_ms DESC,id DESC LIMIT :size""")
    suspend fun closedMonthPage(accountId: Long, startMs: Long, endMs: Long, time: Long?, id: Long?, size: Int): List<DepositEntity>

    @Query("SELECT COALESCE(SUM(revision),0) FROM term_deposits WHERE savings_account_id=:accountId")
    fun depositRevision(accountId: Long): Flow<Long>
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:accountId AND status=:status AND (:day IS NULL OR start_epoch_day<:day OR (start_epoch_day=:day AND id<:id)) ORDER BY start_epoch_day DESC,id DESC LIMIT :size")
    suspend fun depositPage(accountId: Long, status: String, day: Long?, id: Long?, size: Int): List<DepositEntity>
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:account_id AND status=:status ORDER BY start_epoch_day DESC,id DESC LIMIT :limit")
    fun deposits(account_id: Long, limit: Int, status:String): Flow<List<DepositEntity>>
    @Query("SELECT * FROM term_deposits WHERE id=:id")
    suspend fun deposit(id: Long): DepositEntity?
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:account_id AND id=:id")
    fun observe_deposit(account_id: Long, id: Long): Flow<DepositEntity?>
    @Insert suspend fun insert_deposit(value: DepositEntity): Long
    @Query("UPDATE term_deposits SET revision=revision+1,status='CLOSED',close_cash_linked=:cashAccountId IS NOT NULL,close_cash_account_id=:cashAccountId,close_operation_id=:operationId,closed_at_ms=:now,updated_at_ms=:now WHERE id=:id AND status='OPEN'")
    suspend fun close_deposit(id: Long, cashAccountId: Long?, operationId: String, now: Long): Int
    @Query("UPDATE term_deposits SET principal_minor=:principal,annual_rate_percent_e8=:rate,start_epoch_day=:start,end_epoch_day=:end,expected_interest_minor=:interest,open_cash_linked=:openCashAccountId IS NOT NULL,close_cash_linked=CASE WHEN :closedKnown=0 THEN NULL ELSE :closeCashAccountId IS NOT NULL END,open_cash_account_id=:openCashAccountId,close_cash_account_id=:closeCashAccountId,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision")
    suspend fun edit_deposit(id: Long, old_revision: Long, principal: Long, rate: Long, start: Long, end: Long,
        interest: Long, openCashAccountId: Long?, closeCashAccountId: Long?, closedKnown: Boolean, now: Long): Int
}
