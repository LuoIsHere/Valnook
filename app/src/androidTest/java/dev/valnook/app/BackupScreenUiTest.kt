package dev.valnook.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.cloud.CloudBackupDescriptor
import dev.valnook.domain.cloud.CloudBackupRuntimeState
import dev.valnook.domain.cloud.CloudBackupService
import dev.valnook.domain.cloud.UnavailableCloudBackupService
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.portability.*
import dev.valnook.domain.repository.SettingsRepository
import dev.valnook.feature.backup.BackupScreen
import dev.valnook.feature.backup.BackupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class BackupScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun connected_name_and_email_wrap_within_a_compact_screen() {
        val display = "Alex · long.synthetic.email.address.for.layout@example.com"
        val cloud = object : CloudBackupService by UnavailableCloudBackupService {
            override val cloudAllowed = true
            override fun observeState() = flowOf(CloudBackupRuntimeState(connected = true, accountDisplay = display))
        }
        val settings = object : SettingsRepository { override fun observeSettings() = flowOf(AppSettings()) }
        val vm = BackupViewModel(UnavailableDataPortability, settings, false, cloud)
        rule.setContent { ValnookTheme {
            Box(Modifier.width(280.dp)) { BackupScreen(vm, false) }
        } }
        val node = rule.onNodeWithTag("cloud-account-display").performScrollTo().assertTextEquals(display)
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        org.junit.Assert.assertEquals(2, layouts.single().lineCount)
        org.junit.Assert.assertFalse(layouts.single().didOverflowWidth)
    }

    @Test fun prebuilt_demo_database_upgrades_without_cloud_connection() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val name = "onedrive-demo-check-${java.util.UUID.randomUUID()}.db"
        val database = dev.valnook.data.database.ValnookDatabase.fromAsset(context, name, "database/valnook-demo-v12.db")
        try {
            org.junit.Assert.assertEquals(dev.valnook.data.database.DATABASE_SCHEMA_VERSION, database.openHelper.readableDatabase.version)
            org.junit.Assert.assertNull(database.cloudBackup().state().account_reference)
            org.junit.Assert.assertFalse(database.cloudBackup().state().automatic_enabled)
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun microsoft_connect_disconnect_and_reconnect_are_locally_simulated() {
        val cloudState = MutableStateFlow(CloudBackupRuntimeState())
        var connections = 0
        var signOuts = 0
        val auth = object : dev.valnook.feature.backup.CloudAuthorization {
            override suspend fun connect(activity: android.app.Activity): dev.valnook.feature.backup.CloudAuthorizationOutcome {
                connections++
                return dev.valnook.feature.backup.CloudAuthorizationOutcome.Granted(
                    dev.valnook.domain.cloud.CloudAuthorizationGrant("synthetic", dev.valnook.domain.cloud.CloudProvider.ONEDRIVE))
            }
            override suspend fun disconnect(): Boolean { signOuts++; return true }
            override suspend fun managementUrl() = "https://account.live.com/consent/Manage"
        }
        val cloud = object : CloudBackupService by UnavailableCloudBackupService {
            override val cloudAllowed = true
            override fun observeState() = cloudState
            override suspend fun connect(grant: dev.valnook.domain.cloud.CloudAuthorizationGrant) {
                org.junit.Assert.assertEquals(dev.valnook.domain.cloud.CloudProvider.ONEDRIVE, grant.provider)
                cloudState.value = CloudBackupRuntimeState(connected = true, accountDisplay = "Synthetic account")
            }
            override suspend fun disconnect() { cloudState.value = CloudBackupRuntimeState() }
        }
        val settings = object : SettingsRepository { override fun observeSettings() = flowOf(AppSettings()) }
        val vm = BackupViewModel(UnavailableDataPortability, settings, false, cloud, auth)
        rule.setContent { ValnookTheme { BackupScreen(vm, false) } }
        rule.onNodeWithTag("cloud-connect").performScrollTo().performClick()
        rule.onNodeWithTag("cloud-disconnect").performScrollTo().performClick()
        rule.onNodeWithTag("cloud-connect").performScrollTo().performClick()
        rule.waitForIdle()
        org.junit.Assert.assertEquals(2, connections)
        org.junit.Assert.assertEquals(1, signOuts)
        rule.onAllNodesWithText("Google", substring = true).assertCountEquals(0)
    }

    @Test fun demo_has_only_spreadsheet_export_and_no_cloud_authorization() {
        val settings = object : SettingsRepository { override fun observeSettings() = flowOf(AppSettings()) }
        val vm = BackupViewModel(UnavailableDataPortability, settings, true)
        rule.setContent { ValnookTheme { BackupScreen(vm, true) } }
        rule.onNodeWithTag("backup-export-excel").assertExists()
        rule.onNodeWithTag("backup-create").assertDoesNotExist()
        rule.onNodeWithTag("backup-restore").assertDoesNotExist()
        rule.onNodeWithTag("cloud-connect").assertDoesNotExist()
        rule.onNodeWithTag("cloud-title-icon").assertDoesNotExist()
    }

    @Test fun cloud_detail_expands_for_the_selected_backup_and_collapses_again() {
        val first = descriptor("first", "first.val_backup", "2026-10-01T08:00:00Z")
        val cloudState = MutableStateFlow(CloudBackupRuntimeState(
            connected = true,
            accountDisplay = "Test account",
            folderName = "Valnook_backup",
            backups = listOf(first)
        ))
        val cloud = object : CloudBackupService by UnavailableCloudBackupService {
            override val cloudAllowed = true
            override fun observeState() = cloudState
        }
        val settings = object : SettingsRepository {
            override fun observeSettings() = flowOf(AppSettings())
        }
        val vm = BackupViewModel(UnavailableDataPortability, settings, demo = false, cloudBackup = cloud)

        rule.setContent { ValnookTheme { BackupScreen(vm, demoMode = false) } }
        rule.onNodeWithTag("cloud-title-icon", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cloud-backup-detail-first").assertDoesNotExist()
        rule.onNodeWithTag("cloud-backup-first").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("cloud-backup-detail-first").assertExists()

        rule.onNodeWithTag("cloud-backup-first").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("cloud-backup-detail-first").assertDoesNotExist()
        rule.onNodeWithTag("cloud-microsoft-attribution").performScrollTo().assertExists()
    }

    @Test fun local_failure_and_saved_result_show_above_the_onedrive_section() {
        val vm = BackupViewModel(FakePortability(prepareFailure = PortabilityErrorCode.INVALID_ARCHIVE),
            emptySettings, demo = false, cloudBackup = connectedCloud())
        rule.setContent { ValnookTheme { BackupScreen(vm, demoMode = false) } }
        rule.runOnIdle { vm.prepareRestore("broken.val_backup") { java.io.ByteArrayInputStream(ByteArray(0)) } }
        val error = rule.onNodeWithTag("backup-error").assertIsDisplayed().getUnclippedBoundsInRoot()
        val title = rule.onNodeWithTag("cloud-title-icon", useUnmergedTree = true).getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(error.bottom < title.top)
        rule.onNodeWithTag("backup-restore").assertIsDisplayed()

        rule.runOnIdle { vm.createBackup(vm.backupRequest(), { java.io.ByteArrayOutputStream() }) }
        val saved = rule.onNodeWithTag("backup-completed").assertIsDisplayed().getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(saved.bottom < rule.onNodeWithTag("cloud-title-icon", useUnmergedTree = true)
            .getUnclippedBoundsInRoot().top)
        rule.onNodeWithTag("backup-error").assertDoesNotExist()
    }

    @Test fun restore_preview_scrolls_into_view_and_its_error_sits_below_the_confirm_button() {
        val vm = BackupViewModel(FakePortability(commitFailure = PortabilityErrorCode.CONFIRMATION_MISMATCH),
            emptySettings, demo = false, cloudBackup = connectedCloud())
        rule.setContent { ValnookTheme { BackupScreen(vm, demoMode = false) } }
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag("cloud-microsoft-attribution"))
        rule.onNodeWithTag("backup-restore").assertIsNotDisplayed()
        rule.runOnIdle { vm.prepareRestore("ok.val_backup") { java.io.ByteArrayInputStream(ByteArray(0)) } }
        rule.waitForIdle()
        rule.onNodeWithTag("backup-restore-preview").assertIsDisplayed()

        rule.onNodeWithTag("backup-continue").performScrollTo().performClick()
        rule.runOnIdle { vm.updateConfirmation("123456") }
        rule.onNodeWithTag("backup-confirm-restore").performScrollTo().performClick()
        rule.waitForIdle()
        val confirm = rule.onNodeWithTag("backup-confirm-restore").assertIsDisplayed().getUnclippedBoundsInRoot()
        val error = rule.onNodeWithTag("backup-error").assertIsDisplayed().getUnclippedBoundsInRoot()
        org.junit.Assert.assertTrue(error.top >= confirm.bottom)
    }

    @Test fun failed_attempt_shows_its_reason_under_the_latest_result() {
        val cloudState = MutableStateFlow(CloudBackupRuntimeState(connected = true, accountDisplay = "Test account",
            attemptState = dev.valnook.domain.cloud.BackupAttemptState.FAILED,
            latestError = dev.valnook.domain.cloud.CloudBackupError.UPLOAD_FAILED))
        val cloud = object : CloudBackupService by UnavailableCloudBackupService {
            override val cloudAllowed = true
            override fun observeState() = cloudState
        }
        val vm = BackupViewModel(UnavailableDataPortability, emptySettings, demo = false, cloudBackup = cloud)
        rule.setContent { ValnookTheme { BackupScreen(vm, demoMode = false) } }
        val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
        rule.onNodeWithTag("cloud-latest-reason").performScrollTo()
            .assertTextEquals(resources.getString(dev.valnook.feature.backup.R.string.cloud_reason_upload))

        cloudState.value = cloudState.value.copy(latestError = dev.valnook.domain.cloud.CloudBackupError.DRIVE_PERMISSION)
        rule.onNodeWithTag("cloud-latest-reason")
            .assertTextEquals(resources.getString(dev.valnook.feature.backup.R.string.cloud_error_drive_permission))

        cloudState.value = cloudState.value.copy(attemptState = dev.valnook.domain.cloud.BackupAttemptState.SUCCEEDED,
            latestError = null)
        rule.onNodeWithTag("cloud-latest-reason").assertDoesNotExist()
        cloudState.value = cloudState.value.copy(attemptState = dev.valnook.domain.cloud.BackupAttemptState.WAITING_NETWORK,
            latestError = dev.valnook.domain.cloud.CloudBackupError.NO_NETWORK)
        rule.onNodeWithTag("cloud-latest-reason").assertDoesNotExist()
    }

    private val emptySettings = object : SettingsRepository { override fun observeSettings() = flowOf(AppSettings()) }

    private fun connectedCloud() = object : CloudBackupService by UnavailableCloudBackupService {
        override val cloudAllowed = true
        override fun observeState() = flowOf(CloudBackupRuntimeState(connected = true, accountDisplay = "Test account",
            folderName = "Valnook_backup",
            backups = (1..12).map { descriptor("b$it", "b$it.val_backup", "2026-10-${10 + it}T08:00:00Z") }))
    }

    private class FakePortability(
        val prepareFailure: PortabilityErrorCode? = null,
        val commitFailure: PortabilityErrorCode? = null
    ) : DataPortability {
        override val backupAndRestoreAllowed = true
        override suspend fun createBackup(backupId: String, output: java.io.OutputStream,
            progress: (PortabilityProgress) -> Unit) =
            PortableFileResult("saved.val_backup", 1, "00", 1, "2026-10-14T08:00:00Z", outputVerified = false)
        override suspend fun prepareRestore(input: java.io.InputStream, sourceName: String,
            progress: (PortabilityProgress) -> Unit): RestorePreview {
            prepareFailure?.let { throw PortabilityException(it) }
            return RestorePreview("candidate", sourceName, "00", "backup", "2026-10-14T08:00:00Z", "0.0.17", 17,
                "test", 19, 1, 7, 1, 1, 1, 3, 0, null, true, emptyList())
        }
        override suspend fun issueRestoreChallenge(candidateId: String) = RestoreChallenge(candidateId, "123456")
        override suspend fun commitRestore(candidateId: String, challenge: String, confirmation: String,
            progress: (PortabilityProgress) -> Unit): RestoreResult {
            commitFailure?.let { throw PortabilityException(it) }
            return RestoreResult("attempt", 1, "2026-10-14T08:00:00Z")
        }
        override suspend fun cancelRestore(candidateId: String) = Unit
        override suspend fun exportWorkbook(reportId: String, language: dev.valnook.domain.model.AppLanguage,
            demo: Boolean, output: java.io.OutputStream, progress: (PortabilityProgress) -> Unit) =
            throw PortabilityException(PortabilityErrorCode.INTERNAL)
    }
    private fun descriptor(id: String, fileName: String, createdAt: String) = CloudBackupDescriptor(
        fileId = id,
        fileName = fileName,
        snapshotCreatedAtUtc = createdAt,
        driveCreatedAtUtc = createdAt,
        byteCount = 1024,
        appVersion = "0.0.6",
        internalBuildRevision = "test",
        databaseSchemaVersion = 10,
        formatVersion = 1,
        dataSchemaVersion = 1,
        verificationState = "verified",
        compatible = true
    )
}
