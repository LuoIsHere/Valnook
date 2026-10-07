package dev.valnook.data.cloud

import android.content.Context
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.domain.cloud.*
import java.util.UUID

/** Debug-only real-service harness. Never opens the production backup folder. */
class LiveOneDriveTestSession(
    context: Context,
    database: ValnookDatabase,
    portability: RoomPortabilityEngine,
    access: CloudAccessProvider,
    scheduler: BackupScheduler,
    runId: String
) {
    init { require(UUID.fromString(runId).toString() == runId) }
    private val folderName = "Valnook_cloud_test_$runId"
    private val api = OneDriveRestApi(backupFolderName = folderName)
    val coordinator = CloudBackupCoordinator(context, database, portability, access, api, scheduler,
        AndroidNetworkUtcClock, AndroidNetworkAvailability(context))

    /** Exception detail is restricted to an HTTP status or a stable, non-personal error code. */
    fun safeFailure(error: Throwable): String {
        var current: Throwable? = error
        repeat(5) {
            when (val value = current) {
                is DriveRequestException -> return "HTTP_${value.statusCode ?: "TRANSPORT"}"
                is CloudBackupException -> if (value.cause == null) return value.error.name
            }
            current = current?.cause
        }
        return error.javaClass.simpleName
    }
}
