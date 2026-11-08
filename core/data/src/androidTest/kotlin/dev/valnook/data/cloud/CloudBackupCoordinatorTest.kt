package dev.valnook.data.cloud

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.domain.cloud.*
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CloudBackupCoordinatorTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var database: ValnookDatabase
    private lateinit var drive: FakeDrive
    private lateinit var scheduler: FakeScheduler
    private lateinit var network: MutableNetwork
    private lateinit var coordinator: CloudBackupCoordinator
    private val networkClock = MutableNetworkClock(Instant.parse("2026-10-03T06:00:00Z").toEpochMilli())

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        drive = FakeDrive()
        scheduler = FakeScheduler()
        network = MutableNetwork(true)
        val portability = RoomPortabilityEngine(context, database,
            Clock.fixed(Instant.parse("2026-10-03T06:00:00Z"), ZoneOffset.UTC),
            AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", 10))
        coordinator = CloudBackupCoordinator(context, database, portability,
            CloudAccessProvider { CloudAccessResult.Granted("synthetic-access-token") }, drive,
            scheduler, networkClock, network)
    }

    @After fun close() { database.close() }

    @Test fun gd2_connection_uses_drive_identity_without_extra_identity_scope() = runBlocking {
        connect()

        val state = coordinator.observeState().first()
        assertTrue(state.connected)
        assertEquals("synthetic-account", state.accountReference)
        assertEquals("Test account", state.accountDisplay)
        assertEquals(1, drive.currentUserCalls)
    }

    @Test fun tm2_drive_https_time_allows_backup_when_platform_network_clock_has_no_cache() = runBlocking {
        val portability = RoomPortabilityEngine(context, database,
            Clock.fixed(Instant.parse("2026-10-03T06:00:00Z"), ZoneOffset.UTC),
            AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", 10))
        val fallback = CloudBackupCoordinator(context, database, portability,
            CloudAccessProvider { CloudAccessResult.Granted("synthetic-access-token") }, drive,
            scheduler, object : NetworkUtcClock {
                override suspend fun nowUtcMs(): Long? = null
            }, network)

        fallback.connect(CloudAuthorizationGrant("synthetic-access-token"))
        fallback.manualBackup()

        assertEquals(BackupAttemptState.SUCCEEDED, fallback.observeState().first().attemptState)
        assertEquals(1, drive.uploadCalls)
        assertTrue(drive.serverUtcCalls >= 2)
    }

    @Test fun gd2_validation_then_shared_rotation_keeps_latest_five_and_only_trashes_oldest() = runBlocking {
        connect()
        repeat(5) { drive.seedVerified(it + 1) }

        coordinator.manualBackup()

        val active = drive.files.filterNot { it.trashed }
            .filter { it.properties["verificationState"] == "verified" }
        assertEquals(5, active.size)
        assertTrue(drive.trashedIds.contains("old-1"))
        assertFalse(drive.permanentDeleteCalled)
        assertEquals(BackupAttemptState.SUCCEEDED, coordinator.observeState().first().attemptState)
        assertTrue(drive.uploadedBytes > 0)
    }

    @Test fun successful_backup_lists_the_folder_once_and_shows_what_rotation_left() = runBlocking {
        connect()
        repeat(5) { drive.seedVerified(it + 1) }
        val before = drive.listCalls

        coordinator.manualBackup()

        // Six managed files in pages of two: one listing is three pages.
        assertEquals(3, drive.listCalls - before)
        val backups = coordinator.observeState().first().backups.map { it.fileId }
        assertEquals(5, backups.size)
        assertFalse("old-1" in backups)
        assertTrue("new-1" in backups)
    }

    @Test fun gd2_failed_verification_preserves_all_five_existing_restore_points() = runBlocking {
        connect()
        repeat(5) { drive.seedVerified(it + 1) }
        drive.corruptUploadChecksum = true

        coordinator.manualBackup()

        assertEquals(5, drive.files.count { !it.trashed && it.properties["verificationState"] == "verified" })
        assertTrue(drive.trashedIds.isEmpty())
        assertEquals(BackupAttemptState.FAILED, coordinator.observeState().first().attemptState)
        assertEquals(CloudBackupError.VERIFY_FAILED, coordinator.observeState().first().latestError)
    }

    @Test fun gd2_list_walks_every_page_and_keeps_incompatible_backup_visible() = runBlocking {
        connect()
        repeat(7) { drive.seedVerified(it + 1, databaseSchema = if (it == 6) 999 else 10) }

        coordinator.refresh()

        val backups = coordinator.observeState().first().backups
        assertEquals(7, backups.size)
        assertFalse(backups.first { it.databaseSchemaVersion == 999 }.compatible)
        assertTrue(drive.listCalls >= 4)
    }

    @Test fun au2_offline_due_cycle_waits_without_claiming_an_attempt() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val scheduled = scheduler.latest!!
        network.connected = false

        val shouldRetry = coordinator.executeScheduled(
            scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule)

        assertTrue(shouldRetry)
        assertEquals(BackupAttemptState.WAITING_NETWORK, coordinator.observeState().first().attemptState)
        assertNull(database.cloudBackup().latestAttempt())
        assertEquals(0, drive.uploadCalls)
    }

    @Test fun au2_due_cycle_that_cannot_get_access_waits_and_says_why() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val scheduled = scheduler.latest!!
        // The token refresh fails on a network blip after the schedule was set.
        val offline = CloudBackupCoordinator(context, database,
            RoomPortabilityEngine(context, database, Clock.fixed(Instant.parse("2026-10-03T06:00:00Z"), ZoneOffset.UTC),
                AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", 10)),
            CloudAccessProvider { CloudAccessResult.Unavailable }, drive, scheduler, networkClock, network)

        val shouldRetry = offline.executeScheduled(scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule)

        assertTrue(shouldRetry)
        val state = offline.observeState().first()
        assertEquals(BackupAttemptState.WAITING_NETWORK, state.attemptState)
        assertEquals(CloudBackupError.AUTH_FAILED, state.latestError)
        assertEquals(0, drive.uploadCalls)
    }

    @Test fun au2_edit_after_scheduling_still_backs_up_when_due() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val scheduled = scheduler.latest!!
        // Every recorded write advances the data generation, as AuditRecorder does.
        database.audit().advanceGeneration(networkClock.value)

        val shouldRetry = coordinator.executeScheduled(
            scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule)

        val state = coordinator.observeState().first()
        val next = scheduler.latest
        assertEquals("due cycle uploaded (retry=$shouldRetry, state=${state.attemptState}, " +
            "next cycle=${next?.cycleId == scheduled.cycleId}/${next?.data})", 1, drive.uploadCalls)
        assertEquals(BackupAttemptState.SUCCEEDED, state.attemptState)
    }

    @Test fun au2_waiting_cycle_backs_up_once_network_returns_after_an_edit() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val scheduled = scheduler.latest!!
        network.connected = false
        assertTrue(coordinator.executeScheduled(
            scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule))
        database.audit().advanceGeneration(networkClock.value)
        network.connected = true

        // WorkManager retries the same request, with the generations captured when it was scheduled.
        val shouldRetry = coordinator.executeScheduled(
            scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule)

        val state = coordinator.observeState().first()
        assertEquals("retried cycle uploaded (retry=$shouldRetry, state=${state.attemptState})",
            1, drive.uploadCalls)
        assertFalse(shouldRetry)
    }

    @Test fun au2_edit_during_automatic_upload_still_finishes_the_backup() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val scheduled = scheduler.latest!!
        drive.afterFirstUploadGate = {
            drive.afterFirstUploadGate = null
            database.audit().advanceGeneration(networkClock.value)
        }

        coordinator.executeScheduled(scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule)

        // The archive is a snapshot taken before the edit, so it is still a valid restore point.
        val state = coordinator.observeState().first()
        val next = scheduler.latest
        assertEquals(BackupAttemptState.SUCCEEDED, state.attemptState)
        assertEquals("verified", drive.files.single().properties["verificationState"])
        assertTrue("a new cycle is scheduled", next != null && next.cycleId != scheduled.cycleId)
    }

    @Test fun au2_schedule_change_during_upload_cancels_without_leaving_progress_on_screen() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val scheduled = scheduler.latest!!
        drive.afterFirstUploadGate = {
            drive.afterFirstUploadGate = null
            coordinator.setIntervalHours(12)
        }

        coordinator.executeScheduled(scheduled.cycleId, scheduled.data, scheduled.connection, scheduled.schedule)

        val state = coordinator.observeState().first()
        assertEquals(BackupAttemptState.CANCELLED, state.attemptState)
        assertEquals(BackupAttemptState.CANCELLED.name, database.cloudBackup().latestAttempt()!!.state)
        // The interval change owns the schedule now.
        val next = scheduler.latest!!
        assertNotEquals(scheduled.cycleId, next.cycleId)
        assertEquals(12 * 3_600_000L, next.due - next.reference)
    }

    @Test fun au2_restore_marker_forces_persistent_pause_and_cancels_schedule() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        database.audit().recordRestoreCommit("synthetic-restore", "0".repeat(64), networkClock.value)

        coordinator.initialize()

        val state = coordinator.observeState().first()
        assertEquals(BackupPauseReason.AFTER_RESTORE, state.pauseReason)
        assertNull(state.nextDueAtUtc)
        assertTrue(scheduler.cancelCalls > 0)
    }

    @Test fun au2_resumed_restore_marker_does_not_pause_again_on_reinitialize() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        database.audit().recordRestoreCommit("synthetic-restore", "0".repeat(64), networkClock.value)
        coordinator.initialize()

        coordinator.resumeAfterRestore()
        val resumedDue = coordinator.observeState().first().nextDueAtUtc
        coordinator.initialize()

        val state = coordinator.observeState().first()
        assertEquals(BackupPauseReason.NONE, state.pauseReason)
        assertNotNull(resumedDue)
        assertEquals(resumedDue, state.nextDueAtUtc)
        assertNotNull(scheduler.latest)
    }

    @Test fun gd2_unknown_upload_is_reconciled_without_uploading_a_duplicate() = runBlocking {
        connect()
        drive.failAfterUploadWithUnknownOutcome = true

        coordinator.manualBackup()

        val unknown = database.cloudBackup().latestAttempt()!!
        assertEquals(BackupAttemptState.UNKNOWN_RESULT.name, unknown.state)
        assertTrue(File(unknown.local_archive_path!!).isFile)
        assertEquals(1, drive.uploadCalls)

        drive.failAfterUploadWithUnknownOutcome = false
        coordinator.manualBackup()

        val reconciled = database.cloudBackup().latestAttempt()!!
        assertEquals(BackupAttemptState.SUCCEEDED.name, reconciled.state)
        assertEquals(1, drive.uploadCalls)
        assertEquals("verified", drive.files.single().properties["verificationState"])
        assertFalse(File(unknown.local_archive_path).exists())
    }

    @Test fun gd2_disconnect_during_upload_prevents_old_cycle_from_overwriting_disconnected_state() = runBlocking {
        connect()
        drive.afterFirstUploadGate = { coordinator.disconnect() }

        coordinator.manualBackup()

        val state = coordinator.observeState().first()
        assertFalse(state.connected)
        assertFalse(state.automaticEnabled)
        assertEquals(BackupAttemptState.CANCELLED,
            database.cloudBackup().latestAttempt()?.state?.let { BackupAttemptState.valueOf(it) })
        assertNull(state.pendingBannerEventId)
    }

    @Test fun au2_enabling_automatic_does_not_clear_restore_pause() = runBlocking {
        connect()
        database.audit().recordRestoreCommit("synthetic-restore", "0".repeat(64), networkClock.value)
        coordinator.initialize()

        coordinator.setAutomatic(true)

        val state = coordinator.observeState().first()
        assertTrue(state.automaticEnabled)
        assertEquals(BackupPauseReason.AFTER_RESTORE, state.pauseReason)
        assertNull(state.nextDueAtUtc)
        assertNull(scheduler.latest)
    }

    @Test fun au2_manual_backup_is_allowed_while_restore_pause_remains_active() = runBlocking {
        connect()
        database.audit().recordRestoreCommit("synthetic-restore", "0".repeat(64), networkClock.value)
        coordinator.initialize()

        coordinator.manualBackup()

        val state = coordinator.observeState().first()
        assertEquals(BackupAttemptState.SUCCEEDED, state.attemptState)
        assertEquals(BackupPauseReason.AFTER_RESTORE, state.pauseReason)
        assertNull(state.nextDueAtUtc)
        assertEquals(1, drive.uploadCalls)
    }

    @Test fun au2_interval_and_manual_success_use_authoritative_network_time() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        assertEquals(networkClock.value + 24L * 3_600_000L, scheduler.latest!!.due)
        assertEquals(networkClock.value, scheduler.latest!!.reference)

        networkClock.value += 5L * 3_600_000L
        coordinator.setIntervalHours(12)
        assertEquals(networkClock.value + 12L * 3_600_000L, scheduler.latest!!.due)
        assertEquals(networkClock.value, scheduler.latest!!.reference)

        networkClock.value += 2L * 3_600_000L
        coordinator.manualBackup()
        assertEquals(networkClock.value + 12L * 3_600_000L, scheduler.latest!!.due)
        assertEquals(networkClock.value, scheduler.latest!!.reference)
        assertEquals(networkClock.value, Instant.parse(
            coordinator.observeState().first().lastSuccessAtUtc).toEpochMilli())
    }

    @Test fun gd2_cloud_download_stages_the_original_portable_archive() = runBlocking {
        connect()
        coordinator.manualBackup()
        val uploaded = drive.files.single { it.properties["verificationState"] == "verified" }

        val staged = coordinator.stageForRestore(uploaded.id)
        val signature = coordinator.openStagedRestore(staged.localId).use { it.readNBytes(4) }

        assertArrayEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04), signature)
        coordinator.releaseStagedRestore(staged.localId)
        assertThrows(CloudBackupException::class.java) { coordinator.openStagedRestore(staged.localId) }
        Unit
    }

    @Test fun au2_failed_upload_does_not_retry_old_cycle_but_next_cycle_can_run() = runBlocking {
        connect()
        coordinator.setAutomatic(true)
        val failedCycle = scheduler.latest!!
        drive.failUpload = DriveRequestException(503, false)

        val retryFailedCycle = coordinator.executeScheduled(
            failedCycle.cycleId, failedCycle.data, failedCycle.connection, failedCycle.schedule)

        assertFalse(retryFailedCycle)
        assertEquals(BackupAttemptState.FAILED, coordinator.observeState().first().attemptState)
        assertEquals(1, drive.uploadCalls)
        val nextCycle = scheduler.latest!!
        assertNotEquals(failedCycle.cycleId, nextCycle.cycleId)

        coordinator.executeScheduled(
            failedCycle.cycleId, failedCycle.data, failedCycle.connection, failedCycle.schedule)
        assertEquals(1, drive.uploadCalls)

        drive.failUpload = null
        coordinator.executeScheduled(
            nextCycle.cycleId, nextCycle.data, nextCycle.connection, nextCycle.schedule)
        assertEquals(2, drive.uploadCalls)
        assertEquals(BackupAttemptState.SUCCEEDED, coordinator.observeState().first().attemptState)
    }

    @Test fun gd2_cleanup_failure_keeps_new_backup_successful_and_records_incomplete_cleanup() = runBlocking {
        connect()
        repeat(5) { drive.seedVerified(it + 1) }
        drive.failTrash = true

        coordinator.manualBackup()

        val state = coordinator.observeState().first()
        assertEquals(BackupAttemptState.SUCCEEDED, state.attemptState)
        assertTrue(state.cleanupIncomplete)
        // Rotation stopped part way, so the list is read again from the drive.
        assertEquals(6, state.backups.size)
        assertEquals(6, drive.files.count { !it.trashed && it.properties["verificationState"] == "verified" })
        assertTrue(drive.trashedIds.isEmpty())
    }

    @Test fun gd2_two_logical_devices_share_one_retention_set() = runBlocking {
        val secondDatabase = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        try {
            val secondPortability = RoomPortabilityEngine(context, secondDatabase,
                Clock.fixed(Instant.parse("2026-10-03T06:00:00Z"), ZoneOffset.UTC),
                AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", 10))
            val second = CloudBackupCoordinator(context, secondDatabase, secondPortability,
                CloudAccessProvider { CloudAccessResult.Granted("second-synthetic-token") }, drive,
                FakeScheduler(), networkClock, network)
            connect()
            second.connect(CloudAuthorizationGrant("second-synthetic-token"))

            repeat(3) { coordinator.manualBackup() }
            repeat(2) { second.manualBackup() }
            coordinator.manualBackup()

            val active = drive.files.filter { !it.trashed && it.properties["verificationState"] == "verified" }
            assertEquals(5, active.size)
            assertEquals(6, drive.uploadCalls)
            assertEquals(listOf("new-1"), drive.trashedIds)
        } finally {
            secondDatabase.close()
        }
    }

    private fun coordinatorForBuild(databaseVersion: Int) = CloudBackupCoordinator(context, database,
        RoomPortabilityEngine(context, database, Clock.fixed(Instant.parse("2026-10-03T06:00:00Z"), ZoneOffset.UTC),
            AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", databaseVersion)),
        CloudAccessProvider { CloudAccessResult.Granted("synthetic-access-token") }, drive, scheduler,
        object : NetworkUtcClock { override suspend fun nowUtcMs() = Instant.parse("2026-10-03T06:00:00Z").toEpochMilli() }, network)

    @Test fun saved_list_is_there_after_a_restart_without_touching_the_network() = runBlocking {
        connect()
        coordinator.manualBackup()
        val saved = coordinator.observeState().first()
        assertEquals(1, saved.backups.size)
        assertTrue(saved.backupsRefreshedAtUtc != null)
        val lists = drive.listCalls
        val restarted = coordinatorForBuild(10)
        assertEquals(saved.backups, restarted.observeState().first().backups)
        assertEquals(saved.backupsRefreshedAtUtc, restarted.observeState().first().backupsRefreshedAtUtc)
        assertEquals(lists, drive.listCalls)
    }

    @Test fun refresh_replaces_the_saved_list_in_the_cloud_order() = runBlocking {
        connect()
        repeat(2) { coordinator.manualBackup() }
        assertEquals(2, coordinator.observeState().first().backups.size)
        val removed = drive.files.removeAt(0).id
        coordinator.refresh()
        val listed = coordinator.observeState().first().backups
        assertEquals(1, listed.size)
        assertFalse(listed.any { it.fileId == removed })
    }

    @Test fun whether_a_backup_can_be_restored_follows_the_running_version() = runBlocking {
        connect()
        coordinator.manualBackup()
        assertTrue(coordinator.observeState().first().backups.single().compatible)
        // The same saved row, read by an older build that cannot open this database version.
        assertFalse(coordinatorForBuild(1).observeState().first().backups.single().compatible)
        assertTrue(coordinatorForBuild(99).observeState().first().backups.single().compatible)
    }

    @Test fun disconnecting_or_connecting_again_clears_the_saved_list() = runBlocking {
        connect()
        coordinator.manualBackup()
        assertEquals(1, database.cloudBackup().observeIndex().first().size)
        coordinator.disconnect()
        assertTrue(database.cloudBackup().observeIndex().first().isEmpty())
        val disconnected = coordinator.observeState().first()
        assertTrue(disconnected.backups.isEmpty())
        assertEquals(null, disconnected.backupsRefreshedAtUtc)
    }

    @Test fun rows_of_another_account_are_never_shown() = runBlocking {
        connect()
        coordinator.manualBackup()
        val row = database.cloudBackup().observeIndex().first().single()
        database.cloudBackup().clearIndex()
        database.cloudBackup().insertIndex(listOf(row.copy(account_reference = "someone-else")))
        assertTrue(coordinator.observeState().first().backups.isEmpty())
    }

    @Test fun a_list_fetched_for_an_old_connection_is_not_saved() = runBlocking {
        connect()
        coordinator.manualBackup()
        val before = database.cloudBackup().observeIndex().first()
        var reconnected = false
        // The connection changes between asking for the list and saving it.
        val racing = CloudBackupCoordinator(context, database,
            RoomPortabilityEngine(context, database, Clock.fixed(Instant.parse("2026-10-03T06:00:00Z"), ZoneOffset.UTC),
                AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", 10)),
            CloudAccessProvider { CloudAccessResult.Granted("synthetic-access-token") }, object : CloudDriveApi by drive {
                override suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): RemoteBackupPage {
                    if (!reconnected) {
                        reconnected = true
                        database.cloudBackup().saveState(database.cloudBackup().state().let { it.copy(connection_generation = it.connection_generation + 1) })
                    }
                    return RemoteBackupPage(emptyList(), null)
                }
            }, scheduler, object : NetworkUtcClock { override suspend fun nowUtcMs() = Instant.parse("2026-10-03T06:00:00Z").toEpochMilli() }, network)
        val error = runCatching { racing.refresh() }.exceptionOrNull() as? CloudBackupException
        assertEquals(CloudBackupError.SESSION_EXPIRED, error?.error)
        assertEquals(before, database.cloudBackup().observeIndex().first())
    }

    @Test fun restoring_a_backup_removed_elsewhere_says_so_and_updates_the_list() = runBlocking {
        connect()
        repeat(2) { coordinator.manualBackup() }
        val gone = coordinator.observeState().first().backups.first().fileId
        drive.files.removeAll { it.id == gone }
        val error = runCatching { coordinator.stageForRestore(gone) }.exceptionOrNull() as? CloudBackupException
        assertEquals(CloudBackupError.BACKUP_NOT_FOUND, error?.error)
        val listed = coordinator.observeState().first().backups
        assertEquals(1, listed.size)
        assertFalse(listed.any { it.fileId == gone })
    }

    @Test fun downloading_a_backup_moved_to_the_recycle_bin_says_so_and_leaves_the_list() = runBlocking {
        connect()
        coordinator.manualBackup()
        val gone = coordinator.observeState().first().backups.single().fileId
        val index = drive.files.indexOfFirst { it.id == gone }
        drive.files[index] = drive.files[index].copy(trashed = true)
        val error = runCatching { coordinator.downloadOriginal(gone, java.io.ByteArrayOutputStream()) }.exceptionOrNull() as? CloudBackupException
        assertEquals(CloudBackupError.BACKUP_NOT_FOUND, error?.error)
        assertTrue(coordinator.observeState().first().backups.isEmpty())
    }

    private suspend fun connect() {
        coordinator.connect(CloudAuthorizationGrant("synthetic-access-token"))
    }

    private class MutableNetworkClock(var value: Long) : NetworkUtcClock {
        override suspend fun nowUtcMs(): Long = value
    }
    private class MutableNetwork(var connected: Boolean) : NetworkAvailability {
        override fun connected() = connected
    }
    private class FakeScheduler : BackupScheduler {
        data class Scheduled(val due: Long, val reference: Long, val cycleId: String, val data: Long,
            val connection: Long, val schedule: Long)
        var latest: Scheduled? = null
        var cancelCalls = 0
        override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String, dataGeneration: Long,
            connectionGeneration: Long, scheduleGeneration: Long) {
            latest = Scheduled(nextDueAtUtcMs, referenceUtcMs, cycleId, dataGeneration, connectionGeneration,
                scheduleGeneration)
        }
        override suspend fun cancel() { cancelCalls++; latest = null }
    }

    private class FakeDrive : CloudDriveApi {
        val files = mutableListOf<RemoteBackupFile>()
        val contents = mutableMapOf<String, ByteArray>()
        val trashedIds = mutableListOf<String>()
        var permanentDeleteCalled = false
        var corruptUploadChecksum = false
        var uploadedBytes = 0L
        var uploadCalls = 0
        var listCalls = 0
        var currentUserCalls = 0
        var serverUtcCalls = 0
        var failAfterUploadWithUnknownOutcome = false
        var failUpload: DriveRequestException? = null
        var failTrash = false
        var afterFirstUploadGate: (suspend () -> Unit)? = null
        private val folder = RemoteBackupFolder("managed-folder", "Valnook_backup")

        override suspend fun serverUtcMs(accessToken: String): Long {
            serverUtcCalls++
            return Instant.parse("2026-10-03T06:00:00Z").toEpochMilli()
        }

        override suspend fun currentUser(accessToken: String): RemoteBackupUser {
            currentUserCalls++
            return RemoteBackupUser("synthetic-account", "Test account")
        }
        override suspend fun resolveOrCreateFolder(accessToken: String) = folder
        override suspend fun upload(accessToken: String, folderId: String, localFile: File,
            fileName: String, appProperties: Map<String, String>,
            beforeRemoteSideEffect: suspend () -> Unit): RemoteBackupFile {
            beforeRemoteSideEffect(); uploadCalls++; uploadedBytes = localFile.length()
            afterFirstUploadGate?.invoke()
            beforeRemoteSideEffect()
            failUpload?.let { throw it }
            val md5 = digest(localFile, "MD5").let { if (corruptUploadChecksum) "0".repeat(32) else it }
            val id = "new-$uploadCalls"
            contents[id] = localFile.readBytes()
            val result = RemoteBackupFile(id, fileName, "2026-10-03T06:00:00Z", localFile.length(), md5,
                listOf(folderId), appProperties, false).also(files::add)
            if (failAfterUploadWithUnknownOutcome) throw DriveRequestException(null, true)
            return result
        }
        override suspend fun metadata(accessToken: String, fileId: String) =
            files.firstOrNull { it.id == fileId } ?: throw DriveRequestException(404, false)
        override suspend fun markVerified(accessToken: String, verified: RemoteBackupFile,
            appProperties: Map<String, String>,
            beforeRemoteSideEffect: suspend () -> Unit): RemoteBackupFile {
            beforeRemoteSideEffect()
            val index = files.indexOfFirst { it.id == verified.id }
            val value = files[index].copy(properties = appProperties)
            files[index] = value
            return value
        }
        override suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): RemoteBackupPage {
            listCalls++
            val offset = pageToken?.toInt() ?: 0
            val page = files.filter { folderId in it.parents && !it.trashed }.drop(offset).take(2)
            val next = (offset + page.size).takeIf { it < files.count { file -> folderId in file.parents && !file.trashed } }
            return RemoteBackupPage(page, next?.toString())
        }
        override suspend fun trash(accessToken: String, fileId: String,
            beforeRemoteSideEffect: suspend () -> Unit) {
            beforeRemoteSideEffect()
            if (failTrash) throw DriveRequestException(503, false)
            trashedIds += fileId
            val index = files.indexOfFirst { it.id == fileId }
            files[index] = files[index].copy(trashed = true)
        }
        override suspend fun download(accessToken: String, fileId: String, output: OutputStream) {
            output.write(contents[fileId] ?: byteArrayOf(1, 2, 3))
        }
        fun seedVerified(order: Int, databaseSchema: Int = 10) {
            files += RemoteBackupFile("old-$order", "Valnook_old_$order.val_backup",
                "2026-09-${order.toString().padStart(2, '0')}T00:00:00Z", 100, "0".repeat(32),
                listOf(folder.id), mapOf(
                    "app" to "valnook", "role" to "backup", "verificationState" to "verified",
                    "backupId" to "backup-$order", "formatVersion" to "1", "dataSchemaVersion" to "1",
                    "databaseSchemaVersion" to databaseSchema.toString(), "appVersion" to "0.0.5",
                    "internalBuildRevision" to "fixture", "snapshotCreatedAtUtc" to "2026-09-01T00:00:00Z"
                ), false)
        }
        private fun digest(file: File, algorithm: String): String {
            val digest = MessageDigest.getInstance(algorithm)
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
