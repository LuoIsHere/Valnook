package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.AuditEventEntity
import dev.valnook.domain.repository.DataMaintenance
import java.util.UUID

/** [clock] dates the clearing like any other write, so tests and the audit trail agree on its day. */
class RoomDataMaintenance(private val db: ValnookDatabase, private val clock: java.time.Clock = java.time.Clock.systemDefaultZone()) : DataMaintenance {
    override suspend fun clearBusinessData() {
        db.withTransaction {
            val maintenance = db.maintenance()
            val now = clock.millis()
            maintenance.clearWalletCards()
            maintenance.clearWalletImages()
            maintenance.clearAuditAccounts()
            maintenance.clearAuditEvents()
            maintenance.clearStatisticsCache()
            maintenance.clearStatisticsBaseline()
            maintenance.clearStatisticsState()
            maintenance.clearPriceHistory()
            maintenance.clearDemoLabels()
            maintenance.clearChildBills()
            maintenance.clearBills()
            maintenance.clearSubcategories()
            maintenance.clearBillCategories()
            maintenance.clearCashEntries()
            maintenance.clearCashMovements()
            maintenance.clearTrades()
            maintenance.clearDeposits()
            maintenance.clearPositions()
            maintenance.clearInstruments()
            maintenance.clearAssetTypes()
            dev.valnook.data.database.ensureDefaultAssetType(db.openHelper.writableDatabase, now)
            maintenance.clearCreditProfiles()
            maintenance.clearCashAccounts()
            maintenance.clearAccounts()
            // Groups are user content too; only a fresh default group remains.
            maintenance.clearAccountGroups()
            db.accountGroups().insert(dev.valnook.data.database.AccountGroupEntity(
                dev.valnook.data.database.AccountGroupEntity.DEFAULT_ID, "", 0, false, now, now))
            maintenance.clearAccountIcons()
            maintenance.clearOperations()
            // Back to the preset categories a new database starts with.
            dev.valnook.data.database.insertDefaultBillCategories(db.openHelper.writableDatabase, now)
            db.overview().clearRates()
            db.fx().clearHistory()
            // Back to a first launch: switch off and the network notice shown again.
            db.fx().saveLocalState(dev.valnook.data.database.FxLocalStateEntity())
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
                business_local_date = java.time.Instant.ofEpochMilli(now).atZone(clock.zone)
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
