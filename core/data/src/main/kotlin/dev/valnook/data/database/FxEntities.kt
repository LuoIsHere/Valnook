package dev.valnook.data.database

import androidx.room.*

/**
 * One row per base, quote and local day, as the rate source answered for that day: [source_date] is the day the
 * source actually published the value (on or before [local_day]) and [rate] its raw text, 1 base = rate quote.
 * Rows are never revised once written.
 */
@Entity(tableName = "fx_rate_history", primaryKeys = ["base_currency", "quote_currency", "local_day"], foreignKeys = [
    ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["base_currency"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["quote_currency"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("quote_currency")])
data class FxRateHistoryEntity(val base_currency: String, val quote_currency: String, val local_day: Long,
    val source_date: Long, val rate: String, val fetched_at_ms: Long)

/** Device-local switch and sync record. Never backed up; restore keeps the switch and forgets the sync record. */
@Entity(tableName = "fx_local_state")
data class FxLocalStateEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(defaultValue = "0") val auto_enabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val notice_dismissed: Boolean = false,
    val last_success_day: Long? = null,
    val last_success_at_ms: Long? = null,
    val last_attempt_at_ms: Long? = null,
    val last_error: String? = null,
    val data_date: Long? = null
)

@Dao
interface FxDao {
    @Query("SELECT * FROM fx_local_state WHERE id=1")
    suspend fun localState(): FxLocalStateEntity?

    @Upsert
    suspend fun saveLocalState(value: FxLocalStateEntity)

    @Query("""UPDATE fx_local_state SET last_success_day=NULL,last_success_at_ms=NULL,last_attempt_at_ms=NULL,
        last_error=NULL,data_date=NULL WHERE id=1""")
    suspend fun clearSyncRecord(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(rows: List<FxRateHistoryEntity>)

    @Query("SELECT * FROM fx_rate_history WHERE base_currency=:base ORDER BY quote_currency,local_day")
    suspend fun history(base: String): List<FxRateHistoryEntity>

    @Query("DELETE FROM fx_rate_history WHERE base_currency<>:base")
    suspend fun deleteHistoryOutside(base: String): Int

    @Query("DELETE FROM fx_rate_history")
    suspend fun clearHistory()
}
