package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StatisticsDao {
    @Query("SELECT source_revision FROM statistics_state WHERE id=1")
    fun observeRevision(): Flow<Long?>

    @Query("SELECT * FROM statistics_state WHERE id=1")
    suspend fun state(): StatisticsStateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertState(value: StatisticsStateEntity)

    @Query("""UPDATE statistics_state SET source_revision=source_revision+1,
        earliest_invalidated_epoch_day=CASE
            WHEN earliest_invalidated_epoch_day IS NULL THEN :epochDay
            WHEN earliest_invalidated_epoch_day>:epochDay THEN :epochDay
            ELSE earliest_invalidated_epoch_day END WHERE id=1""")
    suspend fun markInvalid(epochDay: Long): Int

    @Query("""UPDATE statistics_cache SET source_revision=(SELECT source_revision FROM statistics_state WHERE id=1)
        WHERE epoch_day<(SELECT earliest_invalidated_epoch_day FROM statistics_state WHERE id=1)
        AND rule_version=(SELECT rule_version FROM statistics_state WHERE id=1)""")
    suspend fun promoteUnaffectedCache(): Int

    @Query("""DELETE FROM statistics_cache
        WHERE epoch_day>=(SELECT earliest_invalidated_epoch_day FROM statistics_state WHERE id=1)""")
    suspend fun deleteInvalidatedCache(): Int

    @androidx.room.Transaction
    suspend fun invalidate(epochDay: Long) {
        markInvalid(epochDay)
        deleteInvalidatedCache()
        promoteUnaffectedCache()
    }

    @Query("UPDATE statistics_state SET earliest_invalidated_epoch_day=NULL WHERE id=1 AND source_revision=:revision")
    suspend fun markValid(revision: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(values: List<StatisticsCacheEntity>)

    @Query("""SELECT * FROM statistics_cache WHERE metric=:metric AND epoch_day BETWEEN :firstDay AND :lastDay
        AND source_revision=:revision AND rule_version=:ruleVersion ORDER BY epoch_day""")
    suspend fun cached(metric: String, firstDay: Long, lastDay: Long, revision: Long,
        ruleVersion: Int): List<StatisticsCacheEntity>

    @Query("DELETE FROM statistics_cache WHERE epoch_day>=:epochDay")
    suspend fun deleteCacheFrom(epochDay: Long)

    @Query("DELETE FROM statistics_cache")
    suspend fun clearCache()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrice(value: InstrumentPriceEntity): Long

    @Query("SELECT * FROM instrument_price_history WHERE is_deleted=0 ORDER BY instrument_id,effective_at_ms,id")
    suspend fun prices(): List<InstrumentPriceEntity>

    @Query("SELECT * FROM instrument_price_history WHERE id=:id")
    suspend fun price(id: Long): InstrumentPriceEntity?

    @Query("""UPDATE instrument_price_history SET price_e5=:price,effective_at_ms=:effective,
        revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:expectedRevision AND is_deleted=0""")
    suspend fun editPrice(id: Long, expectedRevision: Long, price: Long, effective: Long, now: Long): Int

    @Query("""SELECT * FROM instrument_price_history WHERE instrument_id=:instrumentId AND is_deleted=0
        AND effective_at_ms<=:now ORDER BY effective_at_ms DESC,id DESC LIMIT 1""")
    suspend fun latestPrice(instrumentId: Long, now: Long): InstrumentPriceEntity?

    @Query("SELECT * FROM cash_entries WHERE is_deleted=0 ORDER BY occurred_at_ms,id")
    suspend fun cashEntries(): List<CashEntryEntity>

    @Query("SELECT * FROM cash_accounts ORDER BY id")
    suspend fun currentCash(): List<CashEntity>

    @Query("SELECT * FROM term_deposits ORDER BY start_epoch_day,id")
    suspend fun deposits(): List<DepositEntity>

    @Query("""SELECT p.id,p.savings_account_id,p.instrument_id,s.currency_code FROM investments p
        JOIN instruments s ON s.id=p.instrument_id ORDER BY p.id""")
    suspend fun positions(): List<StatisticsPositionRow>

    @Query("SELECT * FROM investment_trades WHERE is_deleted=0 ORDER BY investment_id,occurred_at_ms,id")
    suspend fun trades(): List<TradeEntity>

    @Query("SELECT * FROM app_settings WHERE id=1")
    suspend fun settings(): SettingsEntity?

    @Query("SELECT * FROM fx_rates ORDER BY source_currency,target_currency")
    suspend fun rates(): List<FxRateEntity>

    @Query("DELETE FROM statistics_baseline_items")
    suspend fun clearBaselineItems()

    @Query("""INSERT INTO statistics_baseline_items(item_kind,reference_id,account_id,instrument_id,currency_code,amount_long,secondary_long)
        SELECT 'CASH',id,savings_account_id,NULL,currency_code,balance_minor,NULL FROM cash_accounts""")
    suspend fun captureCashBaseline()

    @Query("""INSERT INTO statistics_baseline_items(item_kind,reference_id,account_id,instrument_id,currency_code,amount_long,secondary_long)
        SELECT 'DEPOSIT',id,savings_account_id,NULL,currency_code,principal_minor,NULL FROM term_deposits WHERE status='OPEN'""")
    suspend fun captureDepositBaseline()

    @Query("""INSERT INTO statistics_baseline_items(item_kind,reference_id,account_id,instrument_id,currency_code,amount_long,secondary_long)
        SELECT 'POSITION',p.id,p.savings_account_id,p.instrument_id,s.currency_code,p.holding_quantity_e8,s.current_price_e5
        FROM investments p JOIN instruments s ON s.id=p.instrument_id""")
    suspend fun capturePositionBaseline()

    @Query("DELETE FROM statistics_state")
    suspend fun clearState()

    @Query("DELETE FROM instrument_price_history")
    suspend fun clearPrices()
}
