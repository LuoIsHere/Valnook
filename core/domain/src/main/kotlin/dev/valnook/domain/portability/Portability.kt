package dev.valnook.domain.portability

import dev.valnook.domain.model.AppLanguage
import java.io.InputStream
import java.io.OutputStream

const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_DATA_SCHEMA_VERSION = 2
const val AUDIT_PROTOCOL_VERSION = 1

enum class PortabilityStage {
    PREPARING_SNAPSHOT,
    WRITING_PACKAGE,
    VALIDATING_PACKAGE,
    COPYING_OUTPUT,
    COPYING_INPUT,
    VALIDATING_INPUT,
    RESTORING,
    WRITING_WORKBOOK,
    COMPLETE
}

data class PortabilityProgress(val stage: PortabilityStage, val processedRecords: Long = 0)

data class PortableFileResult(
    val fileName: String,
    val byteCount: Long,
    val sha256: String,
    val recordCount: Long,
    val createdAtUtc: String,
    val outputVerified: Boolean
)

data class RestorePreview(
    val candidateId: String,
    val sourceName: String,
    val packageSha256: String,
    val backupId: String,
    val createdAtUtc: String,
    val appVersion: String,
    val appVersionCode: Long,
    val internalBuildRevision: String,
    val databaseSchemaVersion: Int,
    val formatVersion: Int,
    val dataSchemaVersion: Int,
    val accountCount: Long,
    val cashAccountCount: Long,
    val tradeCount: Long,
    val totalRecordCount: Long,
    val auditEventCount: Long,
    val auditCoverageStartUtc: String?,
    val auditCoverageComplete: Boolean,
    val warnings: List<String>
)

data class RestoreChallenge(val candidateId: String, val value: String)

data class RestoreResult(
    val restoreAttemptId: String,
    val datasetGeneration: Long,
    val committedAtUtc: String
)

enum class PortabilityErrorCode {
    DEMO_RESTRICTED,
    SESSION_EXPIRED,
    JOB_IN_PROGRESS,
    INPUT_UNAVAILABLE,
    OUTPUT_UNAVAILABLE,
    INVALID_ARCHIVE,
    LIMIT_EXCEEDED,
    INCOMPATIBLE_VERSION,
    UNKNOWN_FEATURE,
    MISSING_FILE,
    UNEXPECTED_FILE,
    DUPLICATE_ENTRY,
    HASH_MISMATCH,
    RECORD_COUNT_MISMATCH,
    INVALID_DATA,
    RELATIONSHIP_ERROR,
    SUMMARY_MISMATCH,
    STALE_PREVIEW,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_MISMATCH,
    STORAGE_FULL,
    WORKBOOK_LIMIT,
    WEB_ADMIN_ACTIVE,
    INTERNAL
}

class PortabilityException(
    val errorCode: PortabilityErrorCode,
    cause: Throwable? = null
) : IllegalStateException(errorCode.name, cause)

/** Session-bound local backup, overwrite restore, and analysis export capability. */
interface DataPortability {
    val backupAndRestoreAllowed: Boolean

    suspend fun createBackup(
        backupId: String,
        output: OutputStream,
        progress: (PortabilityProgress) -> Unit = {}
    ): PortableFileResult

    suspend fun prepareRestore(
        input: InputStream,
        sourceName: String,
        progress: (PortabilityProgress) -> Unit = {}
    ): RestorePreview

    suspend fun issueRestoreChallenge(candidateId: String): RestoreChallenge

    suspend fun commitRestore(
        candidateId: String,
        challenge: String,
        confirmation: String,
        progress: (PortabilityProgress) -> Unit = {}
    ): RestoreResult

    suspend fun cancelRestore(candidateId: String)

    suspend fun exportWorkbook(
        reportId: String,
        language: AppLanguage,
        demo: Boolean,
        output: OutputStream,
        progress: (PortabilityProgress) -> Unit = {}
    ): PortableFileResult
}

object UnavailableDataPortability : DataPortability {
    override val backupAndRestoreAllowed: Boolean = false
    private fun unavailable(): Nothing = throw PortabilityException(PortabilityErrorCode.SESSION_EXPIRED)
    override suspend fun createBackup(backupId: String, output: OutputStream,
        progress: (PortabilityProgress) -> Unit): PortableFileResult = unavailable()
    override suspend fun prepareRestore(input: InputStream, sourceName: String,
        progress: (PortabilityProgress) -> Unit): RestorePreview = unavailable()
    override suspend fun issueRestoreChallenge(candidateId: String): RestoreChallenge = unavailable()
    override suspend fun commitRestore(candidateId: String, challenge: String, confirmation: String,
        progress: (PortabilityProgress) -> Unit): RestoreResult = unavailable()
    override suspend fun cancelRestore(candidateId: String) = Unit
    override suspend fun exportWorkbook(reportId: String, language: AppLanguage, demo: Boolean,
        output: OutputStream, progress: (PortabilityProgress) -> Unit): PortableFileResult = unavailable()
}
