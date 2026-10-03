package dev.valnook.feature.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.portability.*
import dev.valnook.domain.repository.SettingsRepository
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OutputRequest(val id: String, val fileName: String)

data class BackupUiState(
    val busy: Boolean = false,
    val stage: PortabilityStage? = null,
    val processedRecords: Long = 0,
    val completedFile: PortableFileResult? = null,
    val preview: RestorePreview? = null,
    val challenge: RestoreChallenge? = null,
    val confirmation: String = "",
    val restoreResult: RestoreResult? = null,
    val error: PortabilityErrorCode? = null
)

class BackupViewModel(
    private val portability: DataPortability,
    private val settings: SettingsRepository,
    private val demo: Boolean
) : ViewModel() {
    private val mutable = MutableStateFlow(BackupUiState())
    val state = mutable.asStateFlow()

    fun backupRequest(): OutputRequest = request("Valnook", ".val_backup")
    fun workbookRequest(): OutputRequest = request(if (demo) "Valnook_Demo" else "Valnook", ".xlsx")

    fun createBackup(
        request: OutputRequest,
        openOutput: () -> OutputStream?,
        openInput: () -> InputStream? = { null }
    ) = runJob {
        if (!portability.backupAndRestoreAllowed) throw PortabilityException(PortabilityErrorCode.DEMO_RESTRICTED)
        var result = withContext(Dispatchers.IO) {
            openOutput()?.use { portability.createBackup(request.id, it, ::progress) }
                ?: throw PortabilityException(PortabilityErrorCode.OUTPUT_UNAVAILABLE)
        }
        result = result.copy(outputVerified = withContext(Dispatchers.IO) {
            verifyProviderCopy(result, openInput)
        })
        mutable.value = BackupUiState(completedFile = result)
    }

    fun exportWorkbook(
        request: OutputRequest,
        openOutput: () -> OutputStream?,
        openInput: () -> InputStream? = { null }
    ) = runJob {
        val language = settings.observeSettings().first().language
        var result = withContext(Dispatchers.IO) {
            openOutput()?.use { portability.exportWorkbook(request.id, language, demo, it, ::progress) }
                ?: throw PortabilityException(PortabilityErrorCode.OUTPUT_UNAVAILABLE)
        }
        result = result.copy(outputVerified = withContext(Dispatchers.IO) {
            verifyProviderCopy(result, openInput)
        })
        mutable.value = BackupUiState(completedFile = result)
    }

    fun prepareRestore(sourceName: String, openInput: () -> InputStream?) = runJob {
        if (!portability.backupAndRestoreAllowed) throw PortabilityException(PortabilityErrorCode.DEMO_RESTRICTED)
        mutable.value.preview?.let { portability.cancelRestore(it.candidateId) }
        val preview = withContext(Dispatchers.IO) {
            openInput()?.use { portability.prepareRestore(it, sourceName, ::progress) }
                ?: throw PortabilityException(PortabilityErrorCode.INPUT_UNAVAILABLE)
        }
        mutable.value = BackupUiState(preview = preview)
    }

    fun requestChallenge() {
        val preview = state.value.preview ?: return
        if (state.value.busy) return
        viewModelScope.launch {
            mutable.value = state.value.copy(busy = true, error = null)
            try {
                val challenge = portability.issueRestoreChallenge(preview.candidateId)
                mutable.value = state.value.copy(busy = false, challenge = challenge, confirmation = "")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PortabilityException) {
                mutable.value = state.value.copy(busy = false, error = error.errorCode)
            }
        }
    }

    fun updateConfirmation(value: String) {
        mutable.value = state.value.copy(confirmation = value.uppercase().take(6), error = null)
    }

    fun commitRestore() {
        val current = state.value
        val preview = current.preview ?: return
        val challenge = current.challenge ?: return
        runJob {
            val result = portability.commitRestore(preview.candidateId, challenge.value,
                state.value.confirmation, ::progress)
            mutable.value = BackupUiState(restoreResult = result)
        }
    }

    fun cancelRestore() {
        val candidateId = state.value.preview?.candidateId
        mutable.value = BackupUiState()
        if (candidateId != null) viewModelScope.launch { portability.cancelRestore(candidateId) }
    }

    private fun runJob(block: suspend () -> Unit) {
        if (state.value.busy) return
        viewModelScope.launch {
            mutable.value = state.value.copy(busy = true, error = null, completedFile = null, restoreResult = null)
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: PortabilityException) {
                mutable.value = state.value.copy(busy = false, stage = null, error = error.errorCode)
            } catch (_: Exception) {
                mutable.value = state.value.copy(busy = false, stage = null, error = PortabilityErrorCode.INTERNAL)
            }
        }
    }

    private fun progress(value: PortabilityProgress) {
        mutable.value = state.value.copy(busy = value.stage != PortabilityStage.COMPLETE,
            stage = value.stage, processedRecords = value.processedRecords, error = null)
    }

    private fun request(prefix: String, extension: String): OutputRequest {
        val id = UUID.randomUUID().toString()
        return OutputRequest(id, "${prefix}_${FILE_TIME.format(Instant.now())}_${id.take(8)}$extension")
    }

    private companion object {
        val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

internal fun verifyProviderCopy(
    expected: PortableFileResult,
    openInput: () -> InputStream?
): Boolean {
    val input = try { openInput() } catch (_: Exception) { return false }
    input ?: return false
    val digest = MessageDigest.getInstance("SHA-256")
    var bytes = 0L
    input.use { source ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            bytes = try { Math.addExact(bytes, read.toLong()) } catch (_: ArithmeticException) {
                throw PortabilityException(PortabilityErrorCode.OUTPUT_UNAVAILABLE)
            }
            digest.update(buffer, 0, read)
        }
    }
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    if (bytes != expected.byteCount || sha256 != expected.sha256) {
        throw PortabilityException(PortabilityErrorCode.OUTPUT_UNAVAILABLE)
    }
    return true
}
