package dev.valnook.app.cloud

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.valnook.data.cloud.CloudBackupCoordinator
import dev.valnook.domain.cloud.BackupScheduler
import java.util.concurrent.TimeUnit

class WorkManagerBackupScheduler(context: Context) : BackupScheduler {
    private val work = WorkManager.getInstance(context.applicationContext)
    override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String, dataGeneration: Long,
        connectionGeneration: Long, scheduleGeneration: Long) {
        val input = Data.Builder()
            .putString(KEY_CYCLE, cycleId)
            .putLong(KEY_DATA_GENERATION, dataGeneration)
            .putLong(KEY_CONNECTION_GENERATION, connectionGeneration)
            .putLong(KEY_SCHEDULE_GENERATION, scheduleGeneration)
            .build()
        val request = OneTimeWorkRequestBuilder<CloudBackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay((nextDueAtUtcMs - referenceUtcMs).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(input)
            .addTag(WORK_NAME)
            .build()
        work.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
    override suspend fun cancel() { work.cancelUniqueWork(WORK_NAME) }

    companion object {
        const val WORK_NAME = "valnook-cloud-backup"
        const val KEY_CYCLE = "cycle_id"
        const val KEY_DATA_GENERATION = "data_generation"
        const val KEY_CONNECTION_GENERATION = "connection_generation"
        const val KEY_SCHEDULE_GENERATION = "schedule_generation"
    }
}

interface CloudBackupWorkerOwner { val cloudBackupCoordinator: CloudBackupCoordinator }

class CloudBackupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val owner = applicationContext as? CloudBackupWorkerOwner ?: return Result.failure()
        val cycle = inputData.getString(WorkManagerBackupScheduler.KEY_CYCLE) ?: return Result.failure()
        val waiting = owner.cloudBackupCoordinator.executeScheduled(
            cycle,
            inputData.getLong(WorkManagerBackupScheduler.KEY_DATA_GENERATION, -1),
            inputData.getLong(WorkManagerBackupScheduler.KEY_CONNECTION_GENERATION, -1),
            inputData.getLong(WorkManagerBackupScheduler.KEY_SCHEDULE_GENERATION, -1)
        )
        // Waiting happens before any upload side effect. A failed upload returns success so this cycle is never retried.
        return if (waiting) Result.retry() else Result.success()
    }
}
