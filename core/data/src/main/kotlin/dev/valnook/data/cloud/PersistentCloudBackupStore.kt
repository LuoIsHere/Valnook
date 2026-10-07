package dev.valnook.data.cloud

import androidx.room.withTransaction
import dev.valnook.data.database.CloudBackupAttemptEntity
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
