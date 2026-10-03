package dev.valnook.feature.backup

import dev.valnook.domain.cloud.CloudBackupDescriptor
import dev.valnook.domain.cloud.CloudBackupRuntimeState
import dev.valnook.domain.cloud.CloudBackupService
import dev.valnook.domain.cloud.CloudRestoreDownload
import dev.valnook.domain.cloud.UnavailableCloudBackupService
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.portability.DataPortability
import dev.valnook.domain.portability.UnavailableDataPortability
import dev.valnook.domain.portability.PortableFileResult
import dev.valnook.domain.portability.PortabilityProgress
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import dev.valnook.domain.portability.PortabilityStage
import dev.valnook.domain.portability.RestorePreview
import dev.valnook.domain.repository.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = object : SettingsRepository {
        override fun observeSettings() = flowOf(AppSettings())
    }

    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }

    @Test fun output_requests_use_unique_job_ids_and_the_published_extensions() {
        val real = BackupViewModel(UnavailableDataPortability, settings, demo = false)
        val first = real.backupRequest()
        val second = real.backupRequest()
        assertNotEquals(first.id, second.id)
        assertTrue(first.fileName.startsWith("Valnook_"))
        assertTrue(first.fileName.endsWith(".val_backup"))
        assertTrue(real.workbookRequest().fileName.endsWith(".xlsx"))

        val demo = BackupViewModel(UnavailableDataPortability, settings, demo = true)
        assertTrue(demo.workbookRequest().fileName.startsWith("Valnook_Demo_"))
    }

    @Test fun provider_reread_verifies_exact_bytes_and_rejects_partial_output() {
        val bytes = "portable-output".toByteArray()
        val expected = PortableFileResult("test", bytes.size.toLong(), sha256(bytes), 1, "now", false)
        assertTrue(verifyProviderCopy(expected) { ByteArrayInputStream(bytes) })
        assertEquals(false, verifyProviderCopy(expected) { null })
        try {
            verifyProviderCopy(expected) { ByteArrayInputStream(bytes.copyOf(bytes.size - 1)) }
            throw AssertionError("Expected mismatched provider output")
        } catch (error: PortabilityException) {
            assertEquals(PortabilityErrorCode.OUTPUT_UNAVAILABLE, error.errorCode)
        }
    }

    @Test fun cloud_restore_preview_clears_the_file_progress_busy_state() = runTest(dispatcher) {
        val descriptor = CloudBackupDescriptor("file", "backup.val_backup", "created", "drive", 1,
            "app", "build", 10, 1, 1, "verified", true)
        val cloudState = MutableStateFlow(CloudBackupRuntimeState(connected = true,
            backups = listOf(descriptor)))
        val cloud = object : CloudBackupService by UnavailableCloudBackupService {
            override val cloudAllowed = true
            override fun observeState() = cloudState
            override suspend fun stageForRestore(fileId: String) = CloudRestoreDownload("local", descriptor.fileName)
            override fun openStagedRestore(localId: String): InputStream = ByteArrayInputStream(byteArrayOf(1))
            override fun releaseStagedRestore(localId: String) = Unit
        }
        val preview = RestorePreview("candidate", descriptor.fileName, "hash", "backup", "created",
            "app", 1, "build", 10, 1, 1, 1, 1, 1, 3, 1, null, true, emptyList())
        val portability = object : DataPortability by UnavailableDataPortability {
            override val backupAndRestoreAllowed = true
            override suspend fun prepareRestore(input: InputStream, sourceName: String,
                progress: (PortabilityProgress) -> Unit): RestorePreview {
                progress(PortabilityProgress(PortabilityStage.VALIDATING_INPUT))
                return preview
            }
        }
        val vm = BackupViewModel(portability, settings, demo = false, cloudBackup = cloud)
        advanceUntilIdle()

        vm.prepareCloudRestore(descriptor)
        val restoredState = withTimeout(5_000) {
            vm.state.first { it.preview != null || it.error != null || it.cloudError != null }
        }

        assertEquals(preview, restoredState.preview)
        assertFalse(restoredState.busy)
        assertNull(restoredState.stage)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
