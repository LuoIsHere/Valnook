package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.repository.DataMaintenance

class RoomDataMaintenance(private val db: ValnookDatabase) : DataMaintenance {
    override suspend fun clearBusinessData() {
        db.withTransaction {
            val maintenance = db.maintenance()
            maintenance.clearStatisticsCache()
            maintenance.clearStatisticsBaseline()
            maintenance.clearStatisticsState()
            maintenance.clearPriceHistory()
            maintenance.clearDemoLabels()
            maintenance.clearCashEntries()
            maintenance.clearCashMovements()
            maintenance.clearTrades()
            maintenance.clearDeposits()
            maintenance.clearPositions()
            maintenance.clearInstruments()
            maintenance.clearAssetTypes()
            maintenance.clearCashAccounts()
            maintenance.clearAccounts()
            maintenance.clearOperations()
            db.overview().clearRates()
            db.overview().clearFinancialSettings()
        }
    }
}
