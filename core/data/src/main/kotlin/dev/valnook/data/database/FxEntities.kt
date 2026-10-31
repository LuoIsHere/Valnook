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

data class FxHistoryRange(val quote: String, val first_day: Long, val last_day: Long)

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

    @Query("SELECT MAX(local_day) FROM fx_rate_history WHERE base_currency=:base")
    suspend fun lastHistoryDay(base: String): Long?

    @Query("SELECT * FROM fx_rate_history WHERE base_currency=:base AND local_day=:day")
    suspend fun historyOn(base: String, day: Long): List<FxRateHistoryEntity>

    @Query("SELECT * FROM fx_rate_history WHERE base_currency=:base AND quote_currency=:quote ORDER BY local_day DESC LIMIT 1")
    suspend fun latestHistory(base: String, quote: String): FxRateHistoryEntity?

    /** Every stored day of [quotes] under [base], for valuing past days at their own rate. */
    @Query("SELECT * FROM fx_rate_history WHERE base_currency=:base AND quote_currency IN (:quotes)")
    suspend fun historyFor(base: String, quotes: List<String>): List<FxRateHistoryEntity>

    @Query("""SELECT * FROM fx_rate_history WHERE base_currency=:base AND quote_currency IN (:quotes)
        AND local_day BETWEEN :firstDay AND :lastDay""")
    suspend fun historyFor(base: String, quotes: List<String>, firstDay: Long, lastDay: Long): List<FxRateHistoryEntity>

    /** First and last stored day of each quote under [base]; history is continuous between them. */
    @Query("""SELECT quote_currency AS quote,MIN(local_day) AS first_day,MAX(local_day) AS last_day FROM fx_rate_history
        WHERE base_currency=:base GROUP BY quote_currency""")
    suspend fun historyRanges(base: String): List<FxHistoryRange>

    /** Each quote's row for its latest stored day under [base]. */
    @Query("""SELECT h.* FROM fx_rate_history h JOIN (SELECT quote_currency AS q,MAX(local_day) AS d FROM fx_rate_history
        WHERE base_currency=:base GROUP BY quote_currency) m ON h.quote_currency=m.q AND h.local_day=m.d
        WHERE h.base_currency=:base""")
    suspend fun latestHistoryPerQuote(base: String): List<FxRateHistoryEntity>

    @Query("UPDATE fx_local_state SET auto_enabled=:on WHERE id=1")
    suspend fun setAutoEnabled(on: Boolean): Int

    @Query("UPDATE fx_local_state SET last_attempt_at_ms=:now,last_error=:error WHERE id=1")
    suspend fun recordFailure(now: Long, error: String): Int

    @Query("""UPDATE fx_local_state SET last_success_day=:day,last_success_at_ms=:now,last_attempt_at_ms=:now,
        last_error=NULL,data_date=:dataDate WHERE id=1""")
    suspend fun recordSuccess(day: Long, now: Long, dataDate: Long?): Int

    /** Currencies the totals depend on: every balance account, every deposit (closed ones too) and every traded instrument. */
    @Query("""SELECT currency_code FROM cash_accounts UNION SELECT currency_code FROM term_deposits
        UNION SELECT i.currency_code FROM instruments i WHERE EXISTS (SELECT 1 FROM investments p
            JOIN investment_trades t ON t.investment_id=p.id WHERE p.instrument_id=i.id AND t.is_deleted=0)""")
    suspend fun inUseCurrencies(): List<String>

    @Query("SELECT * FROM fx_rates WHERE target_currency=:base")
    suspend fun ratesTo(base: String): List<FxRateEntity>

    @Upsert
    suspend fun saveRates(values: List<FxRateEntity>)

    @Query("UPDATE app_settings SET revision=revision+1 WHERE id=1")
    suspend fun bumpSettingsRevision(): Int

    @Query("SELECT base_currency FROM app_settings WHERE id=1")
    suspend fun baseCurrency(): String?
}
