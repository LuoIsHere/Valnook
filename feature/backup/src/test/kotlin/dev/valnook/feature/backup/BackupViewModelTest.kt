package dev.valnook.feature.backup

import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.portability.UnavailableDataPortability
import dev.valnook.domain.portability.PortableFileResult
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import dev.valnook.domain.repository.SettingsRepository
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupViewModelTest {
    private val settings = object : SettingsRepository {
        override fun observeSettings() = flowOf(AppSettings())
    }

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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
