package dev.valnook.data.portability

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

internal data class RestoreCommit(
    val attemptId: String,
    val datasetGeneration: Long,
    val committedAtUtc: String
)

internal enum class RestorePoint {
    AFTER_CLEAR,
    AFTER_CANDIDATE_COPY,
    BEFORE_COMMIT_RECEIPT
}

internal class RestoreImporter(
    private val target: ValnookDatabase,
    private val clock: Clock,
    private val fault: (RestorePoint) -> Unit = {}
) {
    suspend fun commit(staged: StagedRestore, expectedGeneration: Long): RestoreCommit {
        val startedAt = clock.millis()
        check(target.audit().setMaintenance(true, startedAt) == 1)
        val attemptId = UUID.randomUUID().toString()
        try {
            target.withTransaction {
                if (target.audit().generation() != expectedGeneration ||
                    expectedGeneration != staged.generationAtPreview) {
                    throw PortabilityException(PortabilityErrorCode.STALE_PREVIEW)
                }
                clearPortableRows()
                fault(RestorePoint.AFTER_CLEAR)
                copyCandidate(staged.stagingDatabase)
                fault(RestorePoint.AFTER_CANDIDATE_COPY)
                target.maintenance().clearStatisticsCache()
                val state = target.statistics().state()
                if (state != null) {
                    val baselineDay = Instant.ofEpochMilli(state.baseline_at_ms)
                        .atZone(clock.zone).toLocalDate().toEpochDay()
                    target.openHelper.writableDatabase.execSQL(
                        """UPDATE statistics_state SET source_revision=source_revision+1,
                            earliest_invalidated_epoch_day=? WHERE id=1""", arrayOf(baselineDay))
                }
                val committedAt = clock.millis()
                fault(RestorePoint.BEFORE_COMMIT_RECEIPT)
                check(target.audit().recordRestoreCommit(attemptId, staged.packageSha256, committedAt) == 1)
                val cloud = target.cloudBackup().state()
                target.cloudBackup().saveState(cloud.copy(
                    pause_reason = "AFTER_RESTORE",
                    schedule_generation = cloud.schedule_generation + 1,
                    next_due_at_utc_ms = null,
                    scheduled_cycle_id = null,
                    attempt_state = "CANCELLED",
                    observed_restore_attempt_id = attemptId,
                    updated_at_ms = committedAt
                ))
            }
        } catch (error: Exception) {
            runCatching { target.audit().setMaintenance(false, clock.millis()) }
            throw error
        }
        val generation = target.audit().generation()
        return RestoreCommit(attemptId, generation, UTC.format(Instant.ofEpochMilli(clock.millis())))
    }

    private fun clearPortableRows() {
        val db = target.openHelper.writableDatabase
        listOf(
            "audit_event_accounts", "audit_events", "audit_metadata", "statistics_cache",
            "statistics_baseline_items", "statistics_state", "instrument_price_history", "demo_labels",
            "cash_entries", "cash_movements", "investment_trades", "term_deposits", "investments",
            "instruments", "asset_types", "cash_accounts", "savings_accounts", "operations", "fx_rates",
            "app_settings", "currencies"
        ).forEach { table -> db.execSQL("DELETE FROM $table") }
    }

    private fun copyCandidate(source: ValnookDatabase) {
        val sourceDb = source.openHelper.readableDatabase
        val targetDb = target.openHelper.writableDatabase
        copyTable(sourceDb, targetDb, "currencies", listOf("code", "fraction_digits"))
        BackupContract.tables.sortedBy { BackupContract.importOrder.indexOf(it.table) }.forEach { table ->
            copyTable(sourceDb, targetDb, table.table, table.databaseColumns)
        }
        copyTable(sourceDb, targetDb, "statistics_state", listOf(
            "id", "source_revision", "rule_version", "baseline_at_ms", "earliest_invalidated_epoch_day"))
        copyTable(sourceDb, targetDb, "statistics_baseline_items", listOf(
            "item_kind", "reference_id", "account_id", "instrument_id", "currency_code", "amount_long", "secondary_long"))
        copyTable(sourceDb, targetDb, "app_settings", listOf(
            "id", "base_currency", "revision", "language", "gain_loss_scheme", "navigation_order", "navigation_visible"))
        copyTable(sourceDb, targetDb, "fx_rates", listOf(
            "source_currency", "target_currency", "rate", "updated_at_ms"))
        copyTable(sourceDb, targetDb, "audit_metadata", listOf(
            "id", "protocol_version", "tracking_start_ms", "tracking_start_database_version",
            "complete_since_start", "legacy_history_before_start"))
    }

    private fun copyTable(
        source: androidx.sqlite.db.SupportSQLiteDatabase,
        destination: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        columns: List<String>
    ) {
        val list = columns.joinToString(",")
        source.query("SELECT $list FROM $table").use { cursor ->
            while (cursor.moveToNext()) {
                val values = ContentValues(columns.size)
                columns.forEachIndexed { index, name -> copyValue(cursor, index, name, values) }
                val id = destination.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                if (id == -1L) throw PortabilityException(PortabilityErrorCode.RELATIONSHIP_ERROR)
            }
        }
    }

    private fun copyValue(cursor: Cursor, index: Int, name: String, values: ContentValues) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> values.putNull(name)
            Cursor.FIELD_TYPE_INTEGER -> values.put(name, cursor.getLong(index))
            Cursor.FIELD_TYPE_STRING -> values.put(name, cursor.getString(index))
            Cursor.FIELD_TYPE_FLOAT -> values.put(name, cursor.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> throw PortabilityException(PortabilityErrorCode.INVALID_DATA)
        }
    }

    private companion object {
        val UTC: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}
