package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Query

@Dao
interface MaintenanceDao {
    @Query("DELETE FROM cash_entries") suspend fun clearCashEntries()
    @Query("DELETE FROM cash_movements") suspend fun clearCashMovements()
    @Query("DELETE FROM investment_trades") suspend fun clearTrades()
    @Query("DELETE FROM term_deposits") suspend fun clearDeposits()
    @Query("DELETE FROM investments") suspend fun clearPositions()
    @Query("DELETE FROM instruments") suspend fun clearInstruments()
    @Query("DELETE FROM asset_types") suspend fun clearAssetTypes()
    @Query("DELETE FROM cash_accounts") suspend fun clearCashAccounts()
    @Query("DELETE FROM savings_accounts") suspend fun clearAccounts()
    @Query("DELETE FROM operations") suspend fun clearOperations()
}
