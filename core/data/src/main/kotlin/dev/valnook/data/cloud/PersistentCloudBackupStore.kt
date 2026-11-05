package dev.valnook.data.cloud

import androidx.room.withTransaction
import dev.valnook.data.database.CloudBackupAttemptEntity
import dev.valnook.data.database.CloudBackupIndexEntity
import dev.valnook.data.database.CloudBackupStateEntity
import dev.valnook.data.database.ValnookDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PersistentCloudBackupStore(private val database: ValnookDatabase) {
    private val mutex = Mutex()
    fun observe(): Flow<CloudBackupStateEntity> = database.cloudBackup().observeState()
    suspend fun state(): CloudBackupStateEntity = database.cloudBackup().state()
    suspend fun <T> updateReturning(block: (CloudBackupStateEntity) -> Pair<CloudBackupStateEntity, T>): T = mutex.withLock {
        database.withTransaction {
            val (next, result) = block(database.cloudBackup().state())
            database.cloudBackup().saveState(next)
            result
        }
    }
    suspend fun update(block: (CloudBackupStateEntity) -> CloudBackupStateEntity): Unit =
        updateReturning { block(it) to Unit }
    fun observeIndex(): Flow<List<CloudBackupIndexEntity>> = database.cloudBackup().observeIndex()
    /** For a change of connection: the saved list belongs to the old one and goes in the same transaction. */
    suspend fun updateClearingIndex(block: (CloudBackupStateEntity) -> CloudBackupStateEntity): Unit = mutex.withLock {
        database.withTransaction {
            val next = block(database.cloudBackup().state())
            database.cloudBackup().clearIndex()
            database.cloudBackup().saveState(next)
        }
    }
    /** Replaces the saved list. Returns false, changing nothing, if the connection changed while it was being fetched. */
    suspend fun replaceIndex(connectionGeneration: Long, values: List<CloudBackupIndexEntity>, refreshedAtUtcMs: Long): Boolean =
        mutex.withLock {
            database.withTransaction {
                val state = database.cloudBackup().state()
                if (state.connection_generation != connectionGeneration) return@withTransaction false
                database.cloudBackup().clearIndex()
                database.cloudBackup().insertIndex(values)
                database.cloudBackup().saveState(state.copy(backups_refreshed_at_utc_ms = refreshedAtUtcMs))
                true
            }
        }
    suspend fun removeFromIndex(fileId: String) = mutex.withLock { database.cloudBackup().removeFromIndex(fileId) }
    suspend fun claim(value: CloudBackupAttemptEntity): Boolean = mutex.withLock {
        database.withTransaction {
            val claimed = database.cloudBackup().claimAttempt(value) != -1L
            if (claimed) database.cloudBackup().trimAttempts(32)
            claimed
        }
    }
    suspend fun saveAttempt(value: CloudBackupAttemptEntity) = mutex.withLock {
        database.cloudBackup().saveAttempt(value)
    }
    suspend fun attempt(id: String) = database.cloudBackup().attempt(id)
    suspend fun attemptForCycle(cycleId: String) = database.cloudBackup().attemptForCycle(cycleId)
    suspend fun latestAttempt() = database.cloudBackup().latestAttempt()
}
