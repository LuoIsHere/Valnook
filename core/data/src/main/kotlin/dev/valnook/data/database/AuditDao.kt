package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(value: AuditEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAccounts(values: List<AuditEventAccountEntity>)

    @Query("SELECT * FROM audit_events WHERE operation_id=:operationId LIMIT 1")
    suspend fun eventForOperation(operationId: String): AuditEventEntity?

    @Query("SELECT * FROM audit_metadata WHERE id=1")
    suspend fun metadata(): AuditMetadataEntity

    @Query("SELECT * FROM local_maintenance_state WHERE id=1")
    suspend fun maintenanceState(): LocalMaintenanceStateEntity

    @Query("UPDATE local_maintenance_state SET dataset_generation=dataset_generation+1,updated_at_ms=:now WHERE id=1")
    suspend fun advanceGeneration(now: Long): Int

    @Query("SELECT dataset_generation FROM local_maintenance_state WHERE id=1")
    suspend fun generation(): Long

    @Query("""UPDATE local_maintenance_state SET dataset_generation=dataset_generation+1,
        maintenance_in_progress=0,last_restore_attempt_id=:attemptId,
        last_restore_backup_sha256=:packageSha256,last_restore_committed_at_ms=:now,
        upload_pause_reason='RESTORED_DATASET_REQUIRES_USER_UPLOAD',updated_at_ms=:now WHERE id=1""")
    suspend fun recordRestoreCommit(attemptId: String, packageSha256: String, now: Long): Int

    @Query("UPDATE local_maintenance_state SET maintenance_in_progress=:active,updated_at_ms=:now WHERE id=1")
    suspend fun setMaintenance(active: Boolean, now: Long): Int
}
