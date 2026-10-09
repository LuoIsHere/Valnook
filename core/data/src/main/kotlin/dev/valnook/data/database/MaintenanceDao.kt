package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM wallet_cards") suspend fun clearWalletCards()
    @Query("DELETE FROM wallet_card_images") suspend fun clearWalletImages()
    @Query("DELETE FROM audit_event_accounts") suspend fun clearAuditAccounts()
    @Query("DELETE FROM audit_events") suspend fun clearAuditEvents()
    @Query("DELETE FROM statistics_cache") suspend fun clearStatisticsCache()
    @Query("DELETE FROM statistics_baseline_items") suspend fun clearStatisticsBaseline()
    @Query("DELETE FROM statistics_state") suspend fun clearStatisticsState()
    @Query("DELETE FROM instrument_price_history") suspend fun clearPriceHistory()
    @Query("DELETE FROM demo_labels") suspend fun clearDemoLabels()
    @Query("DELETE FROM cash_entries") suspend fun clearCashEntries()
    @Query("DELETE FROM cash_movements") suspend fun clearCashMovements()
    @Query("DELETE FROM investment_trades") suspend fun clearTrades()
    @Query("DELETE FROM term_deposits") suspend fun clearDeposits()
    @Query("DELETE FROM investments") suspend fun clearPositions()
    @Query("DELETE FROM instruments") suspend fun clearInstruments()
    @Query("DELETE FROM asset_types") suspend fun clearAssetTypes()
    @Query("DELETE FROM cash_accounts") suspend fun clearCashAccounts()
    @Query("DELETE FROM credit_account_profiles") suspend fun clearCreditProfiles()
    @Query("DELETE FROM savings_accounts") suspend fun clearAccounts()
    @Query("DELETE FROM account_icon_images") suspend fun clearAccountIcons()
    @Query("DELETE FROM operations") suspend fun clearOperations()
    @Query("""UPDATE audit_metadata SET protocol_version=1,tracking_start_ms=:now,
        tracking_start_database_version=9,complete_since_start=1,legacy_history_before_start=0 WHERE id=1""")
    suspend fun resetAuditCoverage(now: Long): Int
}
