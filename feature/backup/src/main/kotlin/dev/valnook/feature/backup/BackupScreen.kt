package dev.valnook.feature.backup

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.portability.PortabilityErrorCode

@Composable
fun BackupScreen(vm: BackupViewModel, demoMode: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var backupRequest by remember { mutableStateOf<OutputRequest?>(null) }
    var workbookRequest by remember { mutableStateOf<OutputRequest?>(null) }
    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> backupRequest?.let { request ->
        if (uri != null) vm.createBackup(request,
            { context.contentResolver.openOutputStream(uri, "w") },
            { context.contentResolver.openInputStream(uri) })
        backupRequest = null
    } }
    val workbookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> workbookRequest?.let { request ->
        if (uri != null) vm.exportWorkbook(request,
            { context.contentResolver.openOutputStream(uri, "w") },
            { context.contentResolver.openInputStream(uri) })
        workbookRequest = null
    } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareRestore(displayName(context, uri)) { context.contentResolver.openInputStream(uri) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            Text(if (demoMode) stringResource(R.string.backup_demo_description)
                else stringResource(R.string.backup_description), style = MaterialTheme.typography.bodyMedium)
        }
        if (!demoMode) {
            item {
                Button(onClick = {
                    vm.backupRequest().also { backupRequest = it; backupLauncher.launch(it.fileName) }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup-create")) {
                    Text(stringResource(R.string.backup_create_local))
                }
            }
            item {
                Button(onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup-restore")) {
                    Text(stringResource(R.string.backup_restore))
                }
            }
        }
        item {
            Button(onClick = {
                vm.workbookRequest().also { workbookRequest = it; workbookLauncher.launch(it.fileName) }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup-export-excel")) {
                Text(if (demoMode) stringResource(R.string.backup_export_demo_excel)
                    else stringResource(R.string.backup_export_excel))
            }
        }
        if (state.busy) item {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(stageLabel(state.stage))
            }
        }
        state.completedFile?.let { result -> item {
            Text(stringResource(
                if (result.outputVerified) R.string.backup_saved_verified else R.string.backup_saved_unverified,
                result.fileName
            ), color = MaterialTheme.colorScheme.primary)
        } }
        state.preview?.let { preview ->
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.backup_restore_preview), style = MaterialTheme.typography.titleMedium) }
            item { PreviewRow(stringResource(R.string.backup_source), preview.sourceName) }
            item { PreviewRow(stringResource(R.string.backup_created), preview.createdAtUtc) }
            item { PreviewRow(stringResource(R.string.backup_versions),
                "${preview.appVersion} · ${preview.internalBuildRevision} · DB ${preview.databaseSchemaVersion} · F${preview.formatVersion}/D${preview.dataSchemaVersion}") }
            item { PreviewRow(stringResource(R.string.backup_counts),
                "${preview.accountCount} / ${preview.cashAccountCount} / ${preview.tradeCount} / ${preview.auditEventCount}") }
            item { Text(stringResource(R.string.backup_overwrite_warning), color = MaterialTheme.colorScheme.error) }
            if (!preview.auditCoverageComplete) item { Text(stringResource(R.string.backup_audit_incomplete),
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.challenge == null) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TextButton(vm::cancelRestore) { Text(stringResource(R.string.backup_cancel)) }
                    Button(vm::requestChallenge, enabled = !state.busy) { Text(stringResource(R.string.backup_continue)) }
                }
            }
        }
        state.challenge?.let { challenge ->
            item { Text(stringResource(R.string.backup_confirmation_code, challenge.value),
                style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(state.confirmation, vm::updateConfirmation, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.backup_confirmation_input)) }, singleLine = true)
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    TextButton(vm::cancelRestore) { Text(stringResource(R.string.backup_cancel)) }
                    Button(vm::commitRestore, enabled = !state.busy && state.confirmation.length == 6) {
                        Text(stringResource(R.string.backup_confirm_restore))
                    }
                }
            }
        }
        state.restoreResult?.let { result -> item {
            Text(stringResource(R.string.backup_restored, result.committedAtUtc), color = MaterialTheme.colorScheme.primary)
        } }
        state.error?.let { error -> item {
            Text(errorLabel(error), color = MaterialTheme.colorScheme.error)
        } }
    }
}

@Composable private fun PreviewRow(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) }
}

@Composable private fun stageLabel(stage: dev.valnook.domain.portability.PortabilityStage?): String = when (stage) {
    dev.valnook.domain.portability.PortabilityStage.PREPARING_SNAPSHOT -> stringResource(R.string.backup_stage_snapshot)
    dev.valnook.domain.portability.PortabilityStage.WRITING_PACKAGE -> stringResource(R.string.backup_stage_package)
    dev.valnook.domain.portability.PortabilityStage.VALIDATING_PACKAGE,
    dev.valnook.domain.portability.PortabilityStage.VALIDATING_INPUT -> stringResource(R.string.backup_stage_validate)
    dev.valnook.domain.portability.PortabilityStage.COPYING_OUTPUT,
    dev.valnook.domain.portability.PortabilityStage.COPYING_INPUT -> stringResource(R.string.backup_stage_copy)
    dev.valnook.domain.portability.PortabilityStage.RESTORING -> stringResource(R.string.backup_stage_restore)
    dev.valnook.domain.portability.PortabilityStage.WRITING_WORKBOOK -> stringResource(R.string.backup_stage_workbook)
    else -> stringResource(R.string.backup_stage_working)
}

@Composable private fun errorLabel(error: PortabilityErrorCode): String = when (error) {
    PortabilityErrorCode.INVALID_ARCHIVE, PortabilityErrorCode.MISSING_FILE,
    PortabilityErrorCode.UNEXPECTED_FILE, PortabilityErrorCode.DUPLICATE_ENTRY -> stringResource(R.string.backup_error_invalid)
    PortabilityErrorCode.INCOMPATIBLE_VERSION, PortabilityErrorCode.UNKNOWN_FEATURE -> stringResource(R.string.backup_error_version)
    PortabilityErrorCode.HASH_MISMATCH, PortabilityErrorCode.RECORD_COUNT_MISMATCH,
    PortabilityErrorCode.SUMMARY_MISMATCH -> stringResource(R.string.backup_error_corrupt)
    PortabilityErrorCode.STALE_PREVIEW -> stringResource(R.string.backup_error_stale)
    PortabilityErrorCode.CONFIRMATION_MISMATCH, PortabilityErrorCode.CONFIRMATION_REQUIRED -> stringResource(R.string.backup_error_confirmation)
    PortabilityErrorCode.LIMIT_EXCEEDED, PortabilityErrorCode.WORKBOOK_LIMIT -> stringResource(R.string.backup_error_limit)
    PortabilityErrorCode.STORAGE_FULL -> stringResource(R.string.backup_error_space)
    else -> stringResource(R.string.backup_error_generic)
}

private fun displayName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()
