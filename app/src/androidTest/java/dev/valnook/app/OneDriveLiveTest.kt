package dev.valnook.app

import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.app.cloud.MicrosoftAuthorizationGateway
import dev.valnook.app.di.currentBuildInfo
import dev.valnook.data.cloud.LiveOneDriveTestSession
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomOverview
import dev.valnook.domain.cloud.*
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before
import org.junit.Rule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest

/** Explicit opt-in only. No credentials, account identity or Graph response bodies in results. */
@HiltAndroidTest
class OneDriveLiveTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Before fun initializeTestApplication() { hilt.inject() }
    @Test fun reauthorized_account_can_refresh_without_interactive_login() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOneDriveReconnect") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = ValnookDatabase.open(context)
        try {
            val reference = database.cloudBackup().state().account_reference
                ?: throw AssertionError("CONNECT_VALNOOK_FIRST")
            val gateway = MicrosoftAuthorizationGateway(context)
            assertTrue(gateway.access(reference) is CloudAccessResult.Granted)
            val engine = RoomPortabilityEngine(context, database, Clock.systemUTC(), currentBuildInfo())
            val cloud = dev.valnook.data.cloud.createCloudBackupCoordinator(context, database, engine, gateway,
                object : BackupScheduler {
                    override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String,
                        dataGeneration: Long, connectionGeneration: Long, scheduleGeneration: Long) = Unit
                    override suspend fun cancel() = Unit
                })
            cloud.refresh()
            assertTrue(cloud.observeState().first().connected)
            if (InstrumentationRegistry.getArguments().getString("verifyAccountEmail") == "true") {
                // Check presence in-process; never include the real profile in test output.
                assertTrue(cloud.observeState().first().accountDisplay.orEmpty().contains('@'))
            }
        } catch (_: Throwable) { throw AssertionError("RECONNECT_SILENT_ACCESS_OR_REFRESH_FAILED") }
        finally { database.close() }
    }

    @Test fun disconnect_current_device_clears_msal_session_and_cancels_backup_schedule() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOneDriveAuth") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = ValnookDatabase.open(context)
        val gateway = MicrosoftAuthorizationGateway(context)
        try {
            val reference = database.cloudBackup().state().account_reference
                ?: throw AssertionError("CONNECT_VALNOOK_FIRST")
            val engine = RoomPortabilityEngine(context, database, Clock.systemUTC(), currentBuildInfo())
            val cloud = dev.valnook.data.cloud.createCloudBackupCoordinator(context, database, engine, gateway,
                dev.valnook.app.cloud.WorkManagerBackupScheduler(context))
            cloud.disconnect()
            assertFalse(cloud.observeState().first().connected)
            assertFalse(database.cloudBackup().state().automatic_enabled)
            assertTrue(gateway.disconnect())
            assertTrue(gateway.access(reference) is CloudAccessResult.AuthorizationRequired)
        } catch (_: Throwable) {
            throw AssertionError("DEVICE_DISCONNECT_VERIFICATION_FAILED")
        } finally { database.close() }
    }

    @Test fun authenticated_cloud_lifecycle_in_isolated_folder() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveOneDrive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runId = UUID.randomUUID().toString()
        instrumentation.sendStatus(0, Bundle().apply { putString("live_run_id", runId) })
        val clock = Clock.systemUTC()
        val source = ValnookDatabase.inMemory(context)
        val target = ValnookDatabase.inMemory(context)
        val engine = RoomPortabilityEngine(context, source, clock, currentBuildInfo())
        val targetEngine = RoomPortabilityEngine(context, target, clock, currentBuildInfo())
        val gateway = MicrosoftAuthorizationGateway(context)
        var scheduled = false
        val scheduler = object : BackupScheduler {
            override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String,
                dataGeneration: Long, connectionGeneration: Long, scheduleGeneration: Long) { scheduled = true }
            override suspend fun cancel() { scheduled = false }
        }
        val live = LiveOneDriveTestSession(context, source, engine, gateway, scheduler, runId)
        val cloud = live.coordinator
        var stage = "READ_CONNECTION"
        fun report(value: String) {
            stage = value
            instrumentation.sendStatus(0, Bundle().apply { putString("live_phase", value) })
        }
        try {
            // Read connection identity only, in-process. Do not open/copy the token cache or ledger.
            val reference = SQLiteDatabase.openDatabase(context.getDatabasePath("valnook.db").path,
                null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT account_reference FROM cloud_backup_state WHERE id=1 AND provider='ONEDRIVE' AND folder_id IS NOT NULL", null).use {
                    check(it.moveToFirst() && !it.isNull(0)) { "CONNECT_VALNOOK_FIRST" }
                    it.getString(0)
                }
            }
            suspend fun grant(): CloudAuthorizationGrant {
                val result = gateway.access(reference)
                check(result is CloudAccessResult.Granted) { "MICROSOFT_REAUTH_REQUIRED" }
                return CloudAuthorizationGrant(result.accessToken, CloudProvider.ONEDRIVE, reference)
            }
            report("SILENT_AUTH")
            val initialGrant = grant()
            report("SEED_SYNTHETIC_LEDGER")
            val fixture = LiveBackupLedgerFixture(clock)
            val ids = fixture.seed(source)
            fixture.verify(source, ids)
            report("CONNECT_ISOLATED_FOLDER")
            cloud.connect(initialGrant)
            assertTrue(cloud.observeState().first().connected)
            assertTrue(cloud.observeState().first().backups.isEmpty())
            report("UPLOAD_AND_VERIFY")
            cloud.manualBackup()
            val state = cloud.observeState().first()
            check(state.attemptState == BackupAttemptState.SUCCEEDED) {
                "BACKUP_${state.attemptState}_${state.latestError}"
            }
            val backup = state.backups.single()
            assertEquals("verified", backup.verificationState)
            assertTrue(backup.compatible)
            report("DOWNLOAD_AND_RESTORE_SYNTHETIC_DATABASE")
            val download = cloud.stageForRestore(backup.fileId)
            val staged = cloud.openStagedRestore(download.localId).use { targetEngine.prepareRestore(it, download.fileName) {} }
            try { targetEngine.commitRestore(staged) {} }
            finally { targetEngine.close(staged); cloud.releaseStagedRestore(download.localId) }
            fixture.verify(target, ids)
            assertEquals(RoomOverview(source).snapshot(), RoomOverview(target).snapshot())
            assertNull(target.cloudBackup().state().account_reference)
            report("AUTOMATIC_SCHEDULE_AND_CONNECTION_RESTART")
            cloud.setAutomatic(true)
            assertTrue(scheduled)
            cloud.disconnect()
            assertFalse(scheduled)
            assertFalse(cloud.observeState().first().connected)
            cloud.connect(grant())
            assertEquals(backup.fileId, cloud.observeState().first().backups.single().fileId)
            assertFalse(cloud.observeState().first().automaticEnabled)
            report("RETENTION_SIX_UPLOADS_KEEP_FIVE")
            repeat(5) { index ->
                report("RETENTION_UPLOAD_${index + 2}")
                cloud.manualBackup()
                assertEquals(BackupAttemptState.SUCCEEDED, cloud.observeState().first().attemptState)
            }
            cloud.refresh()
            assertEquals(5, cloud.observeState().first().backups.size)
            assertFalse(cloud.observeState().first().cleanupIncomplete)
            report("PASSED")
        } catch (error: Throwable) {
            // Only safe codes from our own checks. Never attach an SDK/network exception as cause.
            val code = if (error is IllegalStateException && error.message?.matches(Regex("[A-Z_]+")) == true)
                error.message else live.safeFailure(error)
            throw AssertionError("Live OneDrive test failed at $stage ($code)")
        } finally {
            cloud.disconnect()
            source.close(); target.close()
        }
    }
}
