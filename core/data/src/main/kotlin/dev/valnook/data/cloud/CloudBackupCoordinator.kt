package dev.valnook.data.cloud

import android.content.Context
import dev.valnook.data.database.CloudBackupAttemptEntity
import dev.valnook.data.database.CloudBackupStateEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.BackupContract
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.domain.cloud.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class CloudBackupCoordinator internal constructor(
    context: Context,
    private val database: ValnookDatabase,
    private val portability: RoomPortabilityEngine,
    private val accessProvider: CloudAccessProvider,
    private val drive: CloudDriveApi,
    private val scheduler: BackupScheduler,
    private val networkClock: NetworkUtcClock,
    private val network: NetworkAvailability
) : CloudBackupService {
    private val appContext = context.applicationContext
    private val store = PersistentCloudBackupStore(database)
    private val remote = MutableStateFlow<List<CloudBackupDescriptor>>(emptyList())
    private val running = AtomicBoolean(false)
    private val cloudRoot = File(appContext.cacheDir, "valnook-cloud").also(::secureDirectory)
    private val restoreRoot = File(cloudRoot, "restore").also(::secureDirectory)
    override val cloudAllowed: Boolean = true

    override fun observeState(): Flow<CloudBackupRuntimeState> =
        combine(store.observe(), remote) { state, backups -> state.toDomain(backups) }

    override suspend fun reconcileSchedule() {
        reconcileRestorePause()
        val state = store.state()
        if (!state.automatic_enabled || state.pause_reason != BackupPauseReason.NONE.name ||
            state.folder_id == null || state.next_due_at_utc_ms == null) {
            scheduler.cancel()
            return
        }
        if (running.get()) return
        val now = try {
            trustedNow(state)
        } catch (error: CloudBackupException) {
            val status = if (error.error == CloudBackupError.AUTH_REQUIRED)
                BackupAttemptState.AUTH_REQUIRED else BackupAttemptState.WAITING_NETWORK_TIME
            markWaiting(status, error.error)
            return
        } ?: run {
            markWaiting(BackupAttemptState.WAITING_NETWORK_TIME, CloudBackupError.NETWORK_TIME_UNAVAILABLE)
            return
        }
        if (!network.connected() && now >= state.next_due_at_utc_ms) {
            markWaiting(BackupAttemptState.WAITING_NETWORK, CloudBackupError.NO_NETWORK)
        }
        scheduleExisting(state, now)
    }

    suspend fun initialize() = reconcileSchedule()

    override suspend fun connect(grant: CloudAuthorizationGrant) {
        if (grant.accessToken.isBlank()) {
            throw CloudBackupException(CloudBackupError.AUTH_FAILED)
        }
        val (account, folder) = try {
            drive.currentUser(grant.accessToken) to drive.resolveOrCreateFolder(grant.accessToken)
        } catch (error: DriveRequestException) {
            throw mapDrive(error, CloudBackupError.AUTH_FAILED)
        }
        if (account.accountReference.isBlank() || account.accountDisplay.isBlank()) {
            throw CloudBackupException(CloudBackupError.AUTH_FAILED)
        }
        val now = try { trustedNow(grant.accessToken) } catch (_: Exception) { null }
            ?: System.currentTimeMillis()
        store.update { current -> current.copy(
            account_reference = account.accountReference,
            account_display = account.accountDisplay,
            folder_id = folder.id,
            connection_generation = current.connection_generation + 1,
            schedule_generation = current.schedule_generation + 1,
            next_due_at_utc_ms = null,
            scheduled_cycle_id = null,
            attempt_state = BackupAttemptState.IDLE.name,
            latest_error = null,
            pending_banner_event_id = null,
            pending_banner_error = null,
            updated_at_ms = now
        ) }
        scheduler.cancel()
        refreshWithToken(grant.accessToken)
    }

    override suspend fun disconnect() {
        val now = networkClock.nowUtcMs() ?: System.currentTimeMillis()
        store.update { current -> current.copy(
            account_reference = null,
            account_display = null,
            folder_id = null,
            connection_generation = current.connection_generation + 1,
            automatic_enabled = false,
            schedule_generation = current.schedule_generation + 1,
            next_due_at_utc_ms = null,
            scheduled_cycle_id = null,
            attempt_state = BackupAttemptState.CANCELLED.name,
            latest_error = null,
            pending_banner_event_id = null,
            pending_banner_error = null,
            updated_at_ms = now
        ) }
        remote.value = emptyList()
        scheduler.cancel()
    }

    override suspend fun manualBackup() {
        executeCycle(UUID.randomUUID().toString(), automatic = false, expected = null)
    }

    suspend fun executeScheduled(
        cycleId: String,
        dataGeneration: Long,
        connectionGeneration: Long,
        scheduleGeneration: Long
    ): Boolean {
        executeCycle(cycleId, automatic = true,
            expected = Generation(dataGeneration, connectionGeneration, scheduleGeneration))
        val state = store.state()
        return state.automatic_enabled && state.scheduled_cycle_id == cycleId &&
            state.connection_generation == connectionGeneration &&
            state.schedule_generation == scheduleGeneration && state.attempt_state in WAITING_STATES
    }

    override suspend fun refresh() {
        val state = requireConnection()
        val token = requireAccess(state.account_reference!!)
        refreshWithToken(token)
    }

    override suspend fun setAutomatic(enabled: Boolean) {
        val state = requireConnection()
        val now = trustedNow(state)
        if (now == null) {
            markWaiting(BackupAttemptState.WAITING_NETWORK_TIME, CloudBackupError.NETWORK_TIME_UNAVAILABLE)
            throw CloudBackupException(CloudBackupError.NETWORK_TIME_UNAVAILABLE)
        }
        val next: CloudBackupStateEntity = store.updateReturning { current ->
            if (current.connection_generation != state.connection_generation) stale()
            val generation = current.schedule_generation + 1
            val schedulingAllowed = enabled && current.pause_reason == BackupPauseReason.NONE.name
            current.copy(
                automatic_enabled = enabled,
                schedule_generation = generation,
                next_due_at_utc_ms = if (schedulingAllowed) safeAddHours(now, current.interval_hours) else null,
                scheduled_cycle_id = if (schedulingAllowed) UUID.randomUUID().toString() else null,
                attempt_state = BackupAttemptState.IDLE.name,
                latest_error = null,
                updated_at_ms = now
            ).let { it to it }
        }
        if (next.next_due_at_utc_ms != null) scheduleExisting(next, now) else scheduler.cancel()
    }

    override suspend fun setIntervalHours(hours: Int) {
        if (hours !in 1..MAX_BACKUP_INTERVAL_HOURS) throw CloudBackupException(CloudBackupError.INVALID_INTERVAL)
        val state = requireConnection()
        val now = trustedNow(state)
            ?: throw CloudBackupException(CloudBackupError.NETWORK_TIME_UNAVAILABLE)
        val next: CloudBackupStateEntity = store.updateReturning { current ->
            if (current.connection_generation != state.connection_generation) stale()
            val generation = current.schedule_generation + 1
            current.copy(
                interval_hours = hours,
                schedule_generation = generation,
                next_due_at_utc_ms = if (current.automatic_enabled && current.pause_reason == BackupPauseReason.NONE.name)
                    safeAddHours(now, hours) else null,
                scheduled_cycle_id = if (current.automatic_enabled && current.pause_reason == BackupPauseReason.NONE.name)
                    UUID.randomUUID().toString() else null,
                updated_at_ms = now
            ).let { it to it }
        }
        if (next.next_due_at_utc_ms != null) scheduleExisting(next, now) else scheduler.cancel()
    }

    override suspend fun resumeAfterRestore() {
        val state = requireConnection()
        val now = trustedNow(state)
            ?: throw CloudBackupException(CloudBackupError.NETWORK_TIME_UNAVAILABLE)
        val next: CloudBackupStateEntity = store.updateReturning { current ->
            if (current.connection_generation != state.connection_generation) stale()
            if (current.folder_id == null) throw CloudBackupException(CloudBackupError.AUTH_REQUIRED)
            current.copy(
                pause_reason = BackupPauseReason.NONE.name,
                schedule_generation = current.schedule_generation + 1,
                next_due_at_utc_ms = if (current.automatic_enabled) safeAddHours(now, current.interval_hours) else null,
                scheduled_cycle_id = if (current.automatic_enabled) UUID.randomUUID().toString() else null,
                attempt_state = BackupAttemptState.IDLE.name,
                latest_error = null,
                updated_at_ms = now
            ).let { it to it }
        }
        if (next.next_due_at_utc_ms != null) scheduleExisting(next, now) else scheduler.cancel()
    }

    override suspend fun downloadOriginal(fileId: String, output: OutputStream) {
        val state = requireConnection()
        val token = requireAccess(state.account_reference!!)
        requireManagedFile(token, state.folder_id!!, fileId)
        try {
            drive.download(token, fileId, output)
        } catch (error: DriveRequestException) {
            throw mapDrive(error, CloudBackupError.DOWNLOAD_FAILED)
        }
    }

    override suspend fun stageForRestore(fileId: String): CloudRestoreDownload {
        val state = requireConnection()
        val token = requireAccess(state.account_reference!!)
        val metadata = requireManagedFile(token, state.folder_id!!, fileId)
        if (!metadata.toDescriptor(portability.buildInfo()).compatible) {
            throw CloudBackupException(CloudBackupError.BACKUP_INCOMPATIBLE)
        }
        val localId = UUID.randomUUID().toString()
        val directory = File(restoreRoot, localId).also(::secureDirectory)
        val file = File(directory, "candidate.val_backup")
        try {
            FileOutputStream(file).use { drive.download(token, fileId, it) }
            return CloudRestoreDownload(localId, metadata.name)
        } catch (error: Exception) {
            directory.deleteRecursively()
            if (error is CloudBackupException) throw error
            throw if (error is DriveRequestException) mapDrive(error, CloudBackupError.DOWNLOAD_FAILED)
            else CloudBackupException(CloudBackupError.DOWNLOAD_FAILED, error)
        }
    }

    override fun openStagedRestore(localId: String): InputStream {
        if (!SAFE_ID.matches(localId)) throw CloudBackupException(CloudBackupError.DOWNLOAD_FAILED)
        val file = File(File(restoreRoot, localId), "candidate.val_backup")
        val root = restoreRoot.canonicalFile
        if (!file.canonicalFile.toPath().startsWith(root.toPath()) || !file.isFile) {
            throw CloudBackupException(CloudBackupError.DOWNLOAD_FAILED)
        }
        return FileInputStream(file)
    }

    override fun releaseStagedRestore(localId: String) {
        if (SAFE_ID.matches(localId)) File(restoreRoot, localId).deleteRecursively()
    }

    override suspend fun consumeBanner(eventId: String) {
        store.update { current ->
            if (current.pending_banner_event_id == eventId) current.copy(
                pending_banner_event_id = null, pending_banner_error = null,
                updated_at_ms = System.currentTimeMillis()) else current
        }
    }

    private suspend fun executeCycle(cycleId: String, automatic: Boolean, expected: Generation?) {
        if (!running.compareAndSet(false, true)) return
        var archive: File? = null
        var attempt: CloudBackupAttemptEntity? = null
        try {
            reconcileRestorePause()
            var state = requireConnection()
            val actual = Generation(database.audit().generation(), state.connection_generation, state.schedule_generation)
            if (expected != null && expected != actual) return
            if (automatic && (!state.automatic_enabled || state.pause_reason != BackupPauseReason.NONE.name ||
                    state.scheduled_cycle_id != cycleId)) return
            if (!network.connected()) {
                markWaiting(BackupAttemptState.WAITING_NETWORK, CloudBackupError.NO_NETWORK)
                return
            }
            val token = requireAccess(state.account_reference!!)
            val now = trustedNow(token)
            if (now == null) {
                markWaiting(BackupAttemptState.WAITING_NETWORK_TIME, CloudBackupError.NETWORK_TIME_UNAVAILABLE)
                return
            }
            assertGeneration(actual, cycleId, automatic)
            val reconciled = reconcileUnknown(token, state.folder_id!!, actual, cycleId, automatic)
            if (reconciled != null) {
                attempt = reconciled
                archive = reconciled.local_archive_path?.let(::File)
                attempt = finishVerifiedAttempt(reconciled, token, state.folder_id!!, actual, cycleId, automatic)
                archive?.parentFile?.deleteRecursively()
                archive = null
                return
            }
            store.update { it.copy(attempt_state = BackupAttemptState.PREPARING.name,
                latest_error = null, updated_at_ms = now) }
            val backupId = UUID.randomUUID().toString()
            val attemptId = UUID.randomUUID().toString()
            val directory = File(cloudRoot, "attempt-$attemptId").also(::secureDirectory)
            val archiveFile = File(directory, "archive.val_backup")
            archive = archiveFile
            val result = try {
                withContext(Dispatchers.IO) {
                    FileOutputStream(archiveFile).use { portability.createBackup(backupId, it) {} }
                }
            } catch (error: Exception) {
                finishBeforeUpload(error, automatic, now, actual, cycleId)
                return
            }
            assertGeneration(actual, cycleId, automatic)
            val md5 = digest(archiveFile, "MD5")
            attempt = CloudBackupAttemptEntity(
                attempt_id = attemptId, cycle_id = cycleId, backup_id = backupId,
                data_generation = actual.data, connection_generation = actual.connection,
                schedule_generation = actual.schedule, folder_id = state.folder_id!!,
                planned_drive_file_id = null, drive_file_id = null,
                local_archive_path = archiveFile.absolutePath, archive_sha256 = result.sha256,
                archive_md5 = md5, archive_size = archiveFile.length(), started_at_utc_ms = now,
                finished_at_utc_ms = null, state = BackupAttemptState.UPLOADING.name, error = null)
            if (!store.claim(attempt)) return
            assertGeneration(actual, cycleId, automatic)
            store.update { it.copy(attempt_state = BackupAttemptState.UPLOADING.name,
                last_attempt_at_utc_ms = now, latest_error = null, updated_at_ms = now) }
            val properties = metadata(attempt, result.createdAtUtc)
            val uploaded = try {
                drive.upload(token, state.folder_id!!, archiveFile, result.fileName, properties) {
                    assertGeneration(actual, cycleId, automatic)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attempt = finishAfterUploadError(attempt, error, automatic, actual, cycleId)
                return
            }
            assertGeneration(actual, cycleId, automatic)
            attempt = attempt.copy(drive_file_id = uploaded.id, state = BackupAttemptState.VERIFYING.name)
            store.saveAttempt(attempt)
            store.update { it.copy(attempt_state = BackupAttemptState.VERIFYING.name, updated_at_ms = now) }
            val verified = try {
                assertRemote(uploaded, attempt)
                val marked = drive.markVerified(token, uploaded.id,
                    uploaded.appProperties + ("verificationState" to "verified")) {
                    assertGeneration(actual, cycleId, automatic)
                }
                val confirmed = drive.metadata(token, marked.id)
                assertRemote(confirmed, attempt)
                if (confirmed.appProperties["verificationState"] != "verified") verifyFailed()
                confirmed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                attempt = finishAfterUploadError(attempt, error, automatic, actual, cycleId, verify = true)
                return
            }
            attempt = finishVerifiedAttempt(attempt, token, state.folder_id!!, actual, cycleId, automatic)
            archive.parentFile?.deleteRecursively()
            archive = null
            @Suppress("UNUSED_VARIABLE") val keepReference = verified
        } catch (error: CloudBackupException) {
            if (error.error != CloudBackupError.SESSION_EXPIRED) throw error
            attempt?.let {
                val at = networkClock.nowUtcMs() ?: System.currentTimeMillis()
                val cancelled = it.copy(finished_at_utc_ms = at, state = BackupAttemptState.CANCELLED.name,
                    error = CloudBackupError.SESSION_EXPIRED.name)
                store.saveAttempt(cancelled)
                attempt = cancelled
            }
        } finally {
            if (attempt?.state != BackupAttemptState.UNKNOWN_RESULT.name) archive?.parentFile?.deleteRecursively()
            running.set(false)
        }
    }

    private suspend fun finishBeforeUpload(error: Exception, automatic: Boolean, at: Long,
        generation: Generation, cycleId: String) {
        if (!isGenerationCurrent(generation, cycleId, automatic)) return
        val kind = if (error is dev.valnook.domain.portability.PortabilityException &&
            error.errorCode == dev.valnook.domain.portability.PortabilityErrorCode.STORAGE_FULL)
            CloudBackupError.LOW_STORAGE else CloudBackupError.LOCAL_PREPARATION_FAILED
        onCycleFinished(BackupAttemptState.PREPARATION_FAILED, kind, at, automatic, false, false,
            generation, cycleId)
    }

    private suspend fun finishAfterUploadError(
        attempt: CloudBackupAttemptEntity,
        error: Exception,
        automatic: Boolean,
        generation: Generation,
        cycleId: String,
        verify: Boolean = false
    ): CloudBackupAttemptEntity {
        val at = networkClock.nowUtcMs() ?: System.currentTimeMillis()
        if (!isGenerationCurrent(generation, cycleId, automatic)) {
            return attempt.copy(finished_at_utc_ms = at, state = BackupAttemptState.CANCELLED.name,
                error = CloudBackupError.SESSION_EXPIRED.name).also { store.saveAttempt(it) }
        }
        val unknown = error is DriveRequestException && error.outcomeUnknown
        val cloudError = when {
            error is CloudBackupException -> error.error
            unknown -> CloudBackupError.UPLOAD_UNKNOWN
            verify -> CloudBackupError.VERIFY_FAILED
            error is DriveRequestException && error.statusCode == 401 -> CloudBackupError.AUTH_REQUIRED
            error is DriveRequestException && error.statusCode == 403 -> CloudBackupError.DRIVE_PERMISSION
            else -> CloudBackupError.UPLOAD_FAILED
        }
        val state = if (unknown) BackupAttemptState.UNKNOWN_RESULT else BackupAttemptState.FAILED
        val finished = attempt.copy(finished_at_utc_ms = at, state = state.name, error = cloudError.name)
        store.saveAttempt(finished)
        onCycleFinished(state, cloudError, at, automatic, false, false, generation, cycleId)
        return finished
    }

    private suspend fun onCycleFinished(
        status: BackupAttemptState,
        error: CloudBackupError?,
        at: Long,
        automatic: Boolean,
        success: Boolean,
        cleanupIncomplete: Boolean,
        expected: Generation,
        cycleId: String
    ) {
        val next: CloudBackupStateEntity? = store.updateReturning { current ->
            val validCycle = !automatic || current.scheduled_cycle_id == cycleId
            if (current.connection_generation != expected.connection ||
                current.schedule_generation != expected.schedule || !validCycle || current.folder_id == null ||
                automatic && current.pause_reason == BackupPauseReason.AFTER_RESTORE.name) {
                return@updateReturning current to null
            }
            val shouldSchedule = current.automatic_enabled && current.pause_reason == BackupPauseReason.NONE.name
            val generation = if (shouldSchedule) current.schedule_generation + 1 else current.schedule_generation
            current.copy(
                attempt_state = status.name,
                latest_error = error?.name,
                last_success_at_utc_ms = if (success) at else current.last_success_at_utc_ms,
                cleanup_incomplete = cleanupIncomplete,
                schedule_generation = generation,
                next_due_at_utc_ms = if (shouldSchedule) safeAddHours(at, current.interval_hours) else current.next_due_at_utc_ms,
                scheduled_cycle_id = if (shouldSchedule) UUID.randomUUID().toString() else current.scheduled_cycle_id,
                pending_banner_event_id = if (error != null && status in FAILURE_STATES) UUID.randomUUID().toString()
                    else if (success) null else current.pending_banner_event_id,
                pending_banner_error = if (error != null && status in FAILURE_STATES) error.name
                    else if (success) null else current.pending_banner_error,
                updated_at_ms = at
            ).let { it to it }
        }
        if (next == null) return
        if (next.automatic_enabled && next.pause_reason == BackupPauseReason.NONE.name &&
            next.next_due_at_utc_ms != null) scheduleExisting(next, at)
        else if (automatic) scheduler.cancel()
    }

    private suspend fun finishVerifiedAttempt(
        attempt: CloudBackupAttemptEntity,
        token: String,
        folderId: String,
        generation: Generation,
        cycleId: String,
        automatic: Boolean
    ): CloudBackupAttemptEntity {
        assertGeneration(generation, cycleId, automatic)
        var cleanupIncomplete = false
        try {
            rotate(token, folderId, generation, cycleId, automatic)
        } catch (error: CloudBackupException) {
            if (error.error == CloudBackupError.SESSION_EXPIRED) throw error
            cleanupIncomplete = true
        } catch (_: Exception) {
            cleanupIncomplete = true
        }
        assertGeneration(generation, cycleId, automatic)
        val finishedAt = try {
            trustedNow(token)
        } catch (_: Exception) {
            null
        } ?: attempt.started_at_utc_ms
        val finished = attempt.copy(finished_at_utc_ms = finishedAt,
            state = BackupAttemptState.SUCCEEDED.name, error = null)
        store.saveAttempt(finished)
        try {
            remote.value = listManaged(token, folderId).map { it.toDescriptor(portability.buildInfo()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The verified restore point remains valid; a later refresh can repopulate the list.
        }
        onCycleFinished(BackupAttemptState.SUCCEEDED, null, finishedAt, automatic,
            success = true, cleanupIncomplete = cleanupIncomplete, generation, cycleId)
        return finished
    }

    private suspend fun rotate(token: String, folderId: String, generation: Generation,
        cycleId: String, automatic: Boolean) {
        val valid = listManaged(token, folderId).filter {
            it.appProperties["verificationState"] == "verified"
        }.sortedWith(compareByDescending<DriveFile> { it.createdTime }.thenByDescending { it.id })
        valid.drop(CLOUD_BACKUP_RETENTION_COUNT).forEach { old ->
            assertGeneration(generation, cycleId, automatic)
            drive.trash(token, old.id) { assertGeneration(generation, cycleId, automatic) }
        }
    }

    private suspend fun reconcileUnknown(token: String, folderId: String, generation: Generation,
        cycleId: String, automatic: Boolean): CloudBackupAttemptEntity? {
        val attempt = store.latestAttempt()?.takeIf {
            it.state == BackupAttemptState.UNKNOWN_RESULT.name && it.folder_id == folderId
        } ?: return null
        val found = listManaged(token, folderId).firstOrNull {
            it.id == attempt.drive_file_id || it.appProperties["attemptId"] == attempt.attempt_id
        } ?: return null
        if (runCatching { assertRemote(found, attempt) }.isFailure) return null
        val verified = when (found.appProperties["verificationState"]) {
            "verified" -> found
            "uploaded-unverified" -> try {
                drive.markVerified(token, found.id,
                    found.appProperties + ("verificationState" to "verified")) {
                    assertGeneration(generation, cycleId, automatic)
                }
            } catch (_: Exception) {
                return null
            }
            else -> return null
        }
        val confirmed = try { drive.metadata(token, verified.id) } catch (_: Exception) { return null }
        if (runCatching { assertRemote(confirmed, attempt) }.isFailure ||
            confirmed.appProperties["verificationState"] != "verified") return null
        return attempt.copy(drive_file_id = confirmed.id, state = BackupAttemptState.VERIFYING.name,
            error = null)
    }

    private suspend fun refreshWithToken(token: String) {
        val state = requireConnection()
        remote.value = listManaged(token, state.folder_id!!).map { it.toDescriptor(portability.buildInfo()) }
    }

    private suspend fun listManaged(token: String, folderId: String): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val response = drive.listPage(token, folderId, page)
            result += response.files.filter { it.isManagedBackup(folderId) }
            page = response.nextPageToken
        } while (page != null)
        return result.sortedWith(compareByDescending<DriveFile> { it.createdTime }.thenByDescending { it.id })
    }

    private suspend fun requireManagedFile(token: String, folderId: String, fileId: String): DriveFile {
        val value = try { drive.metadata(token, fileId) } catch (error: DriveRequestException) {
            throw mapDrive(error, CloudBackupError.DOWNLOAD_FAILED)
        }
        if (!value.isManagedBackup(folderId)) throw CloudBackupException(CloudBackupError.DRIVE_PERMISSION)
        return value
    }

    private suspend fun trustedNow(state: CloudBackupStateEntity): Long? {
        networkClock.nowUtcMs()?.let { return it }
        if (!network.connected()) return null
        return trustedNow(requireAccess(state.account_reference
            ?: throw CloudBackupException(CloudBackupError.AUTH_REQUIRED)))
    }

    private suspend fun trustedNow(token: String): Long? {
        networkClock.nowUtcMs()?.let { return it }
        return try {
            drive.serverUtcMs(token)
        } catch (error: DriveRequestException) {
            throw mapDrive(error, CloudBackupError.NETWORK_TIME_UNAVAILABLE)
        }
    }

    private suspend fun requireAccess(accountReference: String): String = when (val result = accessProvider.access(accountReference)) {
        is CloudAccessResult.Granted -> result.accessToken
        CloudAccessResult.AuthorizationRequired -> {
            store.update { it.copy(attempt_state = BackupAttemptState.AUTH_REQUIRED.name,
                latest_error = CloudBackupError.AUTH_REQUIRED.name, updated_at_ms = System.currentTimeMillis()) }
            throw CloudBackupException(CloudBackupError.AUTH_REQUIRED)
        }
        CloudAccessResult.Unavailable -> throw CloudBackupException(CloudBackupError.AUTH_FAILED)
    }

    private suspend fun requireConnection(): CloudBackupStateEntity = store.state().also {
        if (it.folder_id == null || it.account_reference == null) throw CloudBackupException(CloudBackupError.AUTH_REQUIRED)
    }

    private suspend fun assertGeneration(expected: Generation, cycleId: String, automatic: Boolean) {
        if (!isGenerationCurrent(expected, cycleId, automatic)) stale()
    }

    private suspend fun isGenerationCurrent(expected: Generation, cycleId: String,
        automatic: Boolean): Boolean {
        val current = store.state()
        val data = database.audit().generation()
        val validCycle = !automatic || current.scheduled_cycle_id == cycleId
        return data == expected.data && current.connection_generation == expected.connection &&
            current.schedule_generation == expected.schedule && validCycle && current.folder_id != null &&
            (!automatic || current.pause_reason != BackupPauseReason.AFTER_RESTORE.name)
    }

    private suspend fun reconcileRestorePause() {
        val maintenance = database.audit().maintenanceState()
        val restoreId = maintenance.last_restore_attempt_id ?: return
        store.update { state ->
            if (state.observed_restore_attempt_id == restoreId) state
            else state.copy(
                pause_reason = BackupPauseReason.AFTER_RESTORE.name,
                schedule_generation = state.schedule_generation + 1,
                next_due_at_utc_ms = null,
                scheduled_cycle_id = null,
                attempt_state = BackupAttemptState.CANCELLED.name,
                observed_restore_attempt_id = restoreId,
                updated_at_ms = maintenance.last_restore_committed_at_ms ?: System.currentTimeMillis())
        }
    }

    private suspend fun markWaiting(status: BackupAttemptState, error: CloudBackupError) {
        store.update { current -> current.copy(attempt_state = status.name, latest_error = error.name,
            updated_at_ms = System.currentTimeMillis()) }
    }

    private suspend fun scheduleExisting(state: CloudBackupStateEntity, referenceUtcMs: Long) {
        val due = state.next_due_at_utc_ms ?: return
        val cycle = state.scheduled_cycle_id ?: return
        scheduler.replace(due, referenceUtcMs, cycle, database.audit().generation(), state.connection_generation,
            state.schedule_generation)
    }

    private fun metadata(attempt: CloudBackupAttemptEntity, createdAtUtc: String): Map<String, String> {
        val info = portability.buildInfo()
        return linkedMapOf(
            "app" to "valnook",
            "role" to "backup",
            "backupId" to attempt.backup_id,
            "attemptId" to attempt.attempt_id,
            "formatVersion" to BackupContract.FORMAT_VERSION.toString(),
            "dataSchemaVersion" to BackupContract.DATA_SCHEMA_VERSION.toString(),
            "appVersion" to info.appVersion,
            "internalBuildRevision" to info.internalBuildRevision,
            "databaseSchemaVersion" to info.databaseSchemaVersion.toString(),
            "verificationState" to "uploaded-unverified",
            "archiveSha256" to attempt.archive_sha256,
            "snapshotCreatedAtUtc" to createdAtUtc
        )
    }

    private fun assertRemote(file: DriveFile, attempt: CloudBackupAttemptEntity) {
        if (file.trashed || attempt.folder_id !in file.parents || file.size != attempt.archive_size ||
            !file.md5Checksum.equals(attempt.archive_md5, ignoreCase = true) ||
            file.appProperties["app"] != "valnook" || file.appProperties["role"] != "backup" ||
            file.appProperties["backupId"] != attempt.backup_id ||
            file.appProperties["attemptId"] != attempt.attempt_id ||
            file.appProperties["archiveSha256"] != attempt.archive_sha256) verifyFailed()
    }

    private fun verifyFailed(): Nothing = throw CloudBackupException(CloudBackupError.VERIFY_FAILED)
    private fun stale(): Nothing = throw CloudBackupException(CloudBackupError.SESSION_EXPIRED)

    private fun DriveFile.isManagedBackup(folderId: String): Boolean = !trashed && folderId in parents &&
        name.endsWith(".val_backup") && appProperties["app"] == "valnook" && appProperties["role"] == "backup"

    private fun DriveFile.toDescriptor(info: dev.valnook.data.portability.AppBuildInfo): CloudBackupDescriptor {
        val format = appProperties["formatVersion"]?.toIntOrNull() ?: -1
        val schema = appProperties["dataSchemaVersion"]?.toIntOrNull() ?: -1
        val db = appProperties["databaseSchemaVersion"]?.toIntOrNull() ?: -1
        return CloudBackupDescriptor(id, name, appProperties["snapshotCreatedAtUtc"].orEmpty(), createdTime, size,
            appProperties["appVersion"].orEmpty(), appProperties["internalBuildRevision"].orEmpty(), db,
            format, schema, appProperties["verificationState"].orEmpty(),
            appProperties["verificationState"] == "verified" && format == BackupContract.FORMAT_VERSION &&
                schema <= BackupContract.DATA_SCHEMA_VERSION && db <= info.databaseSchemaVersion)
    }

    private fun CloudBackupStateEntity.toDomain(backups: List<CloudBackupDescriptor>) = CloudBackupRuntimeState(
        connected = folder_id != null && account_reference != null,
        accountReference = account_reference,
        accountDisplay = account_display,
        folderName = folder_id?.let { CLOUD_BACKUP_FOLDER_NAME },
        automaticEnabled = automatic_enabled,
        intervalHours = interval_hours,
        pauseReason = enumOr(pause_reason, BackupPauseReason.NONE),
        attemptState = enumOr(attempt_state, BackupAttemptState.IDLE),
        latestError = latest_error?.let { enumOr(it, CloudBackupError.INTERNAL) },
        lastAttemptAtUtc = last_attempt_at_utc_ms?.let(::utc),
        lastSuccessAtUtc = last_success_at_utc_ms?.let(::utc),
        nextDueAtUtc = next_due_at_utc_ms?.let(::utc),
        cleanupIncomplete = cleanup_incomplete,
        backups = backups,
        pendingBannerEventId = pending_banner_event_id,
        pendingBannerError = pending_banner_error?.let { enumOr(it, CloudBackupError.INTERNAL) }
    )

    private fun mapDrive(error: DriveRequestException, fallback: CloudBackupError): CloudBackupException =
        CloudBackupException(when (error.statusCode) {
            401 -> CloudBackupError.AUTH_REQUIRED
            403 -> CloudBackupError.DRIVE_PERMISSION
            404 -> CloudBackupError.DRIVE_FOLDER_NOT_FOUND
            else -> fallback
        }, error)

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeAddHours(start: Long, hours: Int): Long = try {
        Math.addExact(start, Math.multiplyExact(hours.toLong(), 3_600_000L))
    } catch (error: ArithmeticException) {
        throw CloudBackupException(CloudBackupError.INVALID_INTERVAL, error)
    }

    private fun secureDirectory(file: File) {
        if (!file.exists() && !file.mkdirs()) throw CloudBackupException(CloudBackupError.LOW_STORAGE)
        file.setReadable(false, false); file.setWritable(false, false); file.setExecutable(false, false)
        file.setReadable(true, true); file.setWritable(true, true); file.setExecutable(true, true)
    }

    private data class Generation(val data: Long, val connection: Long, val schedule: Long)

    private companion object {
        val SAFE_ID = Regex("[0-9a-fA-F-]{36}")
        val FAILURE_STATES = setOf(BackupAttemptState.FAILED, BackupAttemptState.UNKNOWN_RESULT,
            BackupAttemptState.PREPARATION_FAILED)
        val WAITING_STATES = setOf(BackupAttemptState.WAITING_NETWORK.name,
            BackupAttemptState.WAITING_NETWORK_TIME.name)
        fun utc(value: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(value))
        inline fun <reified T : Enum<T>> enumOr(value: String, fallback: T): T =
            enumValues<T>().firstOrNull { it.name == value } ?: fallback
    }
}

fun createCloudBackupCoordinator(
    context: Context,
    database: ValnookDatabase,
    portability: RoomPortabilityEngine,
    accessProvider: CloudAccessProvider,
    scheduler: BackupScheduler,
    networkUtcClock: NetworkUtcClock = AndroidNetworkUtcClock
): CloudBackupCoordinator = CloudBackupCoordinator(
    context = context,
    database = database,
    portability = portability,
    accessProvider = accessProvider,
    drive = GoogleDriveRestApi(),
    scheduler = scheduler,
    networkClock = networkUtcClock,
    network = AndroidNetworkAvailability(context)
)
