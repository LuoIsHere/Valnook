package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.AuditEventEntity
import dev.valnook.domain.repository.DataMaintenance
import java.util.UUID

class RoomDataMaintenance(private val db: ValnookDatabase) : DataMaintenance {
    override suspend fun clearBusinessData() {
        db.withTransaction {
            val maintenance = db.maintenance()
            val now = System.currentTimeMillis()
            maintenance.clearAuditAccounts()
            maintenance.clearAuditEvents()
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
            maintenance.clearCreditProfiles()
            maintenance.clearCashAccounts()
            maintenance.clearAccounts()
            maintenance.clearOperations()
            db.overview().clearRates()
            db.overview().clearFinancialSettings()
            check(maintenance.resetAuditCoverage(now) == 1)
            val eventId = UUID.randomUUID().toString()
            db.audit().insertEvent(AuditEventEntity(
                event_id = eventId,
                correlation_id = eventId,
                operation_id = null,
                action = "DELETE",
                entity_kind = "DATASET",
                entity_id = null,
                business_at_ms = now,
                business_local_date = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toString(),
                recorded_at_ms = now,
                before_json = null,
                after_json = "{}",
                changed_fields_json = "[]",
                cash_effects_json = "[]",
                source = "USER"
            ))
            check(db.audit().advanceGeneration(now) == 1)
        }
    }
}
