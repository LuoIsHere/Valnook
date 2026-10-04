package dev.valnook.app

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
import dev.valnook.domain.portability.UnavailableDataPortability
import dev.valnook.domain.repository.SettingsRepository
import dev.valnook.feature.backup.BackupScreen
import dev.valnook.feature.backup.BackupViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class BackupScreenUiTest {
    @get:Rule val rule = createComposeRule()

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
        rule.onNodeWithTag("cloud-google-attribution").performScrollTo().assertExists()
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
