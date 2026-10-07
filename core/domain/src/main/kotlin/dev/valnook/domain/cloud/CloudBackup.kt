package dev.valnook.domain.cloud

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow

const val DEFAULT_BACKUP_INTERVAL_HOURS = 24
const val MAX_BACKUP_INTERVAL_HOURS = 720
const val CLOUD_BACKUP_FOLDER_NAME = "Valnook_backup"
const val CLOUD_BACKUP_RETENTION_COUNT = 5

enum class CloudProvider { ONEDRIVE, GOOGLE_DRIVE }

enum class BackupPauseReason { NONE, AFTER_RESTORE }

enum class BackupAttemptState {
    IDLE,
    PREPARING,
    WAITING_NETWORK,
    WAITING_NETWORK_TIME,
    AUTH_REQUIRED,
    UPLOADING,
    VERIFYING,
    SUCCEEDED,
    PREPARATION_FAILED,
    FAILED,
    UNKNOWN_RESULT,
    CANCELLED
}

enum class CloudBackupError {
    NO_NETWORK,
    NETWORK_TIME_UNAVAILABLE,
    AUTH_REQUIRED,
    AUTH_FAILED,
    AUTH_NOT_CONFIGURED,
    CLOUD_QUOTA,
    RATE_LIMITED,
    DRIVE_PERMISSION,
    DRIVE_FOLDER_NOT_FOUND,
    UPLOAD_FAILED,
    UPLOAD_UNKNOWN,
    VERIFY_FAILED,
    DOWNLOAD_FAILED,
    BACKUP_INCOMPATIBLE,
    LOCAL_PREPARATION_FAILED,
    LOW_STORAGE,
    RETENTION_CLEANUP_FAILED,
    SESSION_EXPIRED,
    DEMO_RESTRICTED,
    INVALID_INTERVAL,
    WEB_ADMIN_ACTIVE,
    INTERNAL
}

class CloudBackupException(
    val error: CloudBackupError,
    cause: Throwable? = null
) : IllegalStateException(error.name, cause)

data class CloudAuthorizationGrant(
    val accessToken: String,
    val provider: CloudProvider = CloudProvider.GOOGLE_DRIVE,
    val accountReference: String? = null
) {
    override fun toString() = "CloudAuthorizationGrant(provider=$provider)"
}

sealed interface CloudAccessResult {
    data class Granted(val accessToken: String) : CloudAccessResult {
        override fun toString() = "Granted([redacted])"
    }
    data object AuthorizationRequired : CloudAccessResult
    data object Unavailable : CloudAccessResult
}

/** Obtains a short-lived token in memory. Business code must never persist or log it; the authentication SDK owns its secure cache. */
fun interface CloudAccessProvider {
    suspend fun access(accountReference: String): CloudAccessResult
}

data class CloudBackupDescriptor(
    val fileId: String,
    val fileName: String,
    val snapshotCreatedAtUtc: String,
    val driveCreatedAtUtc: String,
    val byteCount: Long,
    val appVersion: String,
    val internalBuildRevision: String,
    val databaseSchemaVersion: Int,
    val formatVersion: Int,
    val dataSchemaVersion: Int,
    val verificationState: String,
    val compatible: Boolean
)

data class CloudBackupRuntimeState(
    val connected: Boolean = false,
    val accountReference: String? = null,
    val accountDisplay: String? = null,
    val folderName: String? = null,
    val automaticEnabled: Boolean = false,
    val intervalHours: Int = DEFAULT_BACKUP_INTERVAL_HOURS,
    val pauseReason: BackupPauseReason = BackupPauseReason.NONE,
    val attemptState: BackupAttemptState = BackupAttemptState.IDLE,
    val latestError: CloudBackupError? = null,
    val lastAttemptAtUtc: String? = null,
    val lastSuccessAtUtc: String? = null,
    val nextDueAtUtc: String? = null,
    val cleanupIncomplete: Boolean = false,
    val backups: List<CloudBackupDescriptor> = emptyList(),
    val pendingBannerEventId: String? = null,
    val pendingBannerError: CloudBackupError? = null
)

data class CloudRestoreDownload(val localId: String, val fileName: String)

interface BackupScheduler {
    suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String, dataGeneration: Long,
        connectionGeneration: Long, scheduleGeneration: Long)
    suspend fun cancel()
}

interface NetworkUtcClock {
    suspend fun nowUtcMs(): Long?
}

interface CloudBackupService {
    val cloudAllowed: Boolean
    fun observeState(): Flow<CloudBackupRuntimeState>
    suspend fun connect(grant: CloudAuthorizationGrant)
    suspend fun disconnect()
    suspend fun manualBackup()
    suspend fun refresh()
    suspend fun setAutomatic(enabled: Boolean)
    suspend fun setIntervalHours(hours: Int)
    suspend fun resumeAfterRestore()
    suspend fun reconcileSchedule()
    suspend fun downloadOriginal(fileId: String, output: OutputStream)
    suspend fun stageForRestore(fileId: String): CloudRestoreDownload
    fun openStagedRestore(localId: String): InputStream
    fun releaseStagedRestore(localId: String)
    suspend fun consumeBanner(eventId: String)
}

object UnavailableCloudBackupService : CloudBackupService {
    override val cloudAllowed = false
    private fun unavailable(): Nothing = throw CloudBackupException(CloudBackupError.SESSION_EXPIRED)
    override fun observeState(): Flow<CloudBackupRuntimeState> =
        kotlinx.coroutines.flow.flowOf(CloudBackupRuntimeState())
    override suspend fun connect(grant: CloudAuthorizationGrant) = unavailable()
    override suspend fun disconnect() = unavailable()
    override suspend fun manualBackup() = unavailable()
    override suspend fun refresh() = unavailable()
    override suspend fun setAutomatic(enabled: Boolean) = unavailable()
    override suspend fun setIntervalHours(hours: Int) = unavailable()
    override suspend fun resumeAfterRestore() = unavailable()
    override suspend fun reconcileSchedule() = Unit
    override suspend fun downloadOriginal(fileId: String, output: OutputStream) = unavailable()
    override suspend fun stageForRestore(fileId: String): CloudRestoreDownload = unavailable()
    override fun openStagedRestore(localId: String): InputStream = unavailable()
    override fun releaseStagedRestore(localId: String) = Unit
    override suspend fun consumeBanner(eventId: String) = unavailable()
}
