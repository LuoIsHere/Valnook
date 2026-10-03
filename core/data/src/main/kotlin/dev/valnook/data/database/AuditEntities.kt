package dev.valnook.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val AUDIT_PROTOCOL_VERSION = 1

@Entity(
    tableName = "audit_events",
    indices = [Index(value = ["operation_id"], unique = true), Index(value = ["recorded_at_ms", "event_id"])]
)
data class AuditEventEntity(
    @PrimaryKey val event_id: String,
    val event_schema_version: Int = AUDIT_PROTOCOL_VERSION,
    val correlation_id: String,
    val operation_id: String?,
    val action: String,
    val entity_kind: String,
    val entity_id: String?,
    val business_at_ms: Long?,
    val business_local_date: String?,
    val recorded_at_ms: Long,
    val before_json: String?,
    val after_json: String?,
    val changed_fields_json: String,
    val cash_effects_json: String,
    val source: String
)

@Entity(
    tableName = "audit_event_accounts",
    primaryKeys = ["event_id", "account_id"],
    foreignKeys = [ForeignKey(
        entity = AuditEventEntity::class,
        parentColumns = ["event_id"],
        childColumns = ["event_id"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("account_id")]
)
data class AuditEventAccountEntity(val event_id: String, val account_id: Long)

@Entity(tableName = "audit_metadata")
data class AuditMetadataEntity(
    @PrimaryKey val id: Int = 1,
    val protocol_version: Int = AUDIT_PROTOCOL_VERSION,
    val tracking_start_ms: Long,
    val tracking_start_database_version: Int,
    val complete_since_start: Boolean,
    val legacy_history_before_start: Boolean
)

/** Device-local commit/generation state. It is deliberately excluded from portable payloads. */
@Entity(tableName = "local_maintenance_state")
data class LocalMaintenanceStateEntity(
    @PrimaryKey val id: Int = 1,
    val dataset_generation: Long,
    val maintenance_in_progress: Boolean,
    val last_restore_attempt_id: String?,
    val last_restore_backup_sha256: String?,
    val last_restore_committed_at_ms: Long?,
    val upload_pause_reason: String?,
    val updated_at_ms: Long
)
