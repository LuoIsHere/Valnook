package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudBackupDao {
    @Query("SELECT * FROM cloud_backup_state WHERE id=1")
    fun observeState(): Flow<CloudBackupStateEntity>

    @Query("SELECT * FROM cloud_backup_state WHERE id=1")
    suspend fun state(): CloudBackupStateEntity

    @Upsert
    suspend fun saveState(value: CloudBackupStateEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun claimAttempt(value: CloudBackupAttemptEntity): Long

    @Query("SELECT * FROM cloud_backup_attempts WHERE attempt_id=:attemptId")
    suspend fun attempt(attemptId: String): CloudBackupAttemptEntity?

    @Query("SELECT * FROM cloud_backup_attempts WHERE cycle_id=:cycleId LIMIT 1")
    suspend fun attemptForCycle(cycleId: String): CloudBackupAttemptEntity?

    @Query("SELECT * FROM cloud_backup_attempts ORDER BY started_at_utc_ms DESC, attempt_id DESC LIMIT 1")
    suspend fun latestAttempt(): CloudBackupAttemptEntity?

    @Upsert
    suspend fun saveAttempt(value: CloudBackupAttemptEntity)

    @Query("DELETE FROM cloud_backup_attempts WHERE attempt_id NOT IN (SELECT attempt_id FROM cloud_backup_attempts ORDER BY started_at_utc_ms DESC, attempt_id DESC LIMIT :keep)")
    suspend fun trimAttempts(keep: Int)
}
