package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Portfolio reads are independent of the transaction command DAO. */
@Dao
abstract class InvestmentReadDao {
    @Query("SELECT i.*,t.name AS type_name FROM investments i JOIN asset_types t ON t.id=i.asset_type_id WHERE i.savings_account_id=:account_id AND i.position_state=:section ORDER BY i.last_activity_at_ms DESC,i.id DESC LIMIT :limit")
    abstract fun investments(account_id:Long,section:String,limit:Int):Flow<List<InvestmentWithType>>

    @Transaction
    @Query("SELECT i.*,t.name AS type_name FROM investments i JOIN asset_types t ON t.id=i.asset_type_id WHERE i.id=:id")
    abstract fun profit_snapshot(id:Long):Flow<InvestmentProfitSnapshot?>
}

data class InvestmentProfitSnapshot(
    @Embedded val investment:InvestmentWithType,
    @Relation(parentColumn="id",entityColumn="investment_id") val trades:List<TradeEntity>
)
