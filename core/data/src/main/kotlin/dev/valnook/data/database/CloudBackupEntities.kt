package dev.valnook.data.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Device-local Drive connection and scheduler state. Excluded from portable backups. */
@Entity(tableName = "cloud_backup_state")
data class CloudBackupStateEntity(
    @PrimaryKey val id: Int = 1,
    val account_reference: String?,
    val account_display: String?,
    val folder_id: String?,
    val connection_generation: Long,
    val automatic_enabled: Boolean,
    val interval_hours: Int,
    val pause_reason: String,
    val schedule_generation: Long,
    val next_due_at_utc_ms: Long?,
    val scheduled_cycle_id: String?,
    val attempt_state: String,
    val latest_error: String?,
    val last_attempt_at_utc_ms: Long?,
    val last_success_at_utc_ms: Long?,
    val cleanup_incomplete: Boolean,
    val pending_banner_event_id: String?,
    val pending_banner_error: String?,
    val observed_restore_attempt_id: String?,
    val updated_at_ms: Long,
    @ColumnInfo(defaultValue = "'GOOGLE_DRIVE'") val provider: String = "GOOGLE_DRIVE",
    val backups_refreshed_at_utc_ms: Long? = null
)

/**
 * Device-local copy of the last cloud backup list, so it is still there after a restart. Excluded from portable
 * backups. Holds what the cloud reported, not whether a backup can be restored: that depends on the running version.
 */
@Entity(tableName = "cloud_backup_index")
data class CloudBackupIndexEntity(
    @PrimaryKey val file_id: String,
    val provider: String,
    val account_reference: String,
    val folder_id: String,
    val position: Int,
    val file_name: String,
    val snapshot_created_at_utc: String,
    val drive_created_at_utc: String,
    val byte_count: Long,
    val app_version: String,
    val internal_build_revision: String,
    val database_schema_version: Int,
    val format_version: Int,
    val data_schema_version: Int,
    val verification_state: String
)

@Entity(
    tableName = "cloud_backup_attempts",
    indices = [Index("cycle_id", unique = true), Index(value = ["started_at_utc_ms", "attempt_id"])]
)
data class CloudBackupAttemptEntity(
    @PrimaryKey val attempt_id: String,
    val cycle_id: String,
    val backup_id: String,
    val data_generation: Long,
    val connection_generation: Long,
    val schedule_generation: Long,
    val folder_id: String,
    val planned_drive_file_id: String?,
    val drive_file_id: String?,
    val local_archive_path: String,
    val archive_sha256: String,
    val archive_md5: String,
    val archive_size: Long,
    val started_at_utc_ms: Long,
    val finished_at_utc_ms: Long?,
    val state: String,
    val error: String?,
    @ColumnInfo(defaultValue = "'GOOGLE_DRIVE'") val provider: String = "GOOGLE_DRIVE"
)
