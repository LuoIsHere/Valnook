package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DepositDao {
    @Query("SELECT COALESCE(SUM(revision),0) FROM term_deposits WHERE savings_account_id=:accountId")
    fun depositRevision(accountId: Long): Flow<Long>
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:accountId AND status=:status AND (:day IS NULL OR start_epoch_day<:day OR (start_epoch_day=:day AND id<:id)) ORDER BY start_epoch_day DESC,id DESC LIMIT :size")
    suspend fun depositPage(accountId: Long, status: String, day: Long?, id: Long?, size: Int): List<DepositEntity>
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:account_id AND status=:status ORDER BY start_epoch_day DESC,id DESC LIMIT :limit")
    fun deposits(account_id: Long, limit: Int, status:String): Flow<List<DepositEntity>>
    @Query("SELECT id,savings_account_id,currency_code,principal_minor,annual_rate_percent_e8,start_epoch_day,end_epoch_day,interest_rule,calculation_version,rounding_mode,expected_interest_minor,status,open_cash_linked,close_cash_linked,open_operation_id,close_operation_id,closed_at_ms,created_at_ms,updated_at_ms,revision FROM term_deposits WHERE id=:id")
    suspend fun deposit(id: Long): DepositEntity?
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:account_id AND id=:id")
    fun observe_deposit(account_id: Long, id: Long): Flow<DepositEntity?>
    @Insert suspend fun insert_deposit(value: DepositEntity): Long
    @Query("UPDATE term_deposits SET revision=revision+1,status='CLOSED',close_cash_linked=:linked,close_operation_id=:operation_id,closed_at_ms=:now,updated_at_ms=:now WHERE id=:id AND status='OPEN'")
    suspend fun close_deposit(id: Long, linked: Boolean, operation_id: String, now: Long): Int
    @Query("UPDATE term_deposits SET principal_minor=:principal,annual_rate_percent_e8=:rate,start_epoch_day=:start,end_epoch_day=:end,expected_interest_minor=:interest,open_cash_linked=:opened,close_cash_linked=:closed,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision")
    suspend fun edit_deposit(id: Long, old_revision: Long, principal: Long, rate: Long, start: Long, end: Long,
        interest: Long, opened: Boolean, closed: Boolean?, now: Long): Int
}
