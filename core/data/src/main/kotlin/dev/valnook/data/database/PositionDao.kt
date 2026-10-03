package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

const val POSITION_PROJECTION = """SELECT p.*,s.name,s.symbol,s.currency_code,s.asset_type_id,
    s.current_price_e5,s.price_updated_at_ms,t.name AS type_name FROM investments p
    JOIN instruments s ON s.id=p.instrument_id JOIN asset_types t ON t.id=s.asset_type_id"""

@Dao
interface PositionDao {
    @Query("SELECT * FROM investments WHERE id=:id")
    suspend fun investment(id: Long): InvestmentEntity?
    @Query("SELECT * FROM investments WHERE savings_account_id=:accountId AND instrument_id=:instrumentId")
    suspend fun position(accountId: Long, instrumentId: Long): InvestmentEntity?
    @Query(POSITION_PROJECTION + " WHERE p.id=:id")
    fun investment_detail(id: Long): Flow<InvestmentWithType?>
    @Query(POSITION_PROJECTION + " WHERE p.id=:id")
    suspend fun positionSnapshot(id: Long): InvestmentWithType?
    @Query(POSITION_PROJECTION + """ WHERE p.savings_account_id=:accountId AND (
        :section='ALL' OR (:section='HOLDING' AND p.holding_quantity_e8>0) OR
        (:section='CLOSED' AND p.position_state='CLOSED') OR
        (:section='PENDING' AND p.position_state='PENDING'))
        ORDER BY p.last_activity_at_ms DESC,p.id DESC LIMIT :limit""")
    fun accountPositions(accountId: Long, section: String, limit: Int): Flow<List<InvestmentWithType>>
    @Insert suspend fun insert_investment(value: InvestmentEntity): Long
    @Query("""UPDATE investments SET holding_quantity_e8=:holding,revision=revision+1,updated_at_ms=:now,
        remaining_cost=:cost,realized_profit=:realized,chronology_valid=:valid,algorithm_version=:algorithm,
        position_state=CASE WHEN :holding>0 THEN 'HOLDING' WHEN EXISTS(SELECT 1 FROM investment_trades WHERE investment_id=:id AND is_deleted=0) THEN 'CLOSED' ELSE 'PENDING' END,
        last_activity_at_ms=COALESCE((SELECT MAX(occurred_at_ms) FROM investment_trades WHERE investment_id=:id AND is_deleted=0),created_at_ms)
        WHERE id=:id AND revision=:expectedRevision""")
    suspend fun saveCost(id: Long, expectedRevision: Long, holding: Long, cost: String?, realized: String?,
        valid: Boolean, algorithm: Int, now: Long): Int
    @Query("SELECT revision FROM investments WHERE id=:investment_id")
    fun trade_revision(investment_id: Long): Flow<Long>
}
