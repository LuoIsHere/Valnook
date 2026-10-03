package dev.valnook.feature.backup

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.cloud.*
import dev.valnook.domain.portability.PortabilityErrorCode

@Composable
fun BackupScreen(vm: BackupViewModel, demoMode: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    var backupRequest by remember { mutableStateOf<OutputRequest?>(null) }
    var workbookRequest by remember { mutableStateOf<OutputRequest?>(null) }
    var cloudDownload by remember { mutableStateOf<CloudDownloadRequest?>(null) }
    val authLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.completeGoogleConnection(result.data)
    }
    val launchAuthorization: (android.content.IntentSender) -> Unit = { sender ->
        authLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        backupRequest?.let { request ->
            if (uri != null) vm.createBackup(request,
                { context.contentResolver.openOutputStream(uri, "w") },
                { context.contentResolver.openInputStream(uri) })
            backupRequest = null
        }
    }
    val workbookLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri -> workbookRequest?.let { request ->
        if (uri != null) vm.exportWorkbook(request,
            { context.contentResolver.openOutputStream(uri, "w") },
            { context.contentResolver.openInputStream(uri) })
        workbookRequest = null
    } }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> cloudDownload?.let { request ->
        if (uri != null) vm.downloadCloudBackup(request) { context.contentResolver.openOutputStream(uri, "w") }
        cloudDownload = null
    } }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.prepareRestore(displayName(context, uri)) { context.contentResolver.openInputStream(uri) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Text(if (demoMode) stringResource(R.string.backup_demo_description)
            else stringResource(R.string.backup_description), style = MaterialTheme.typography.bodyMedium) }
        if (!demoMode) {
            item { Button(onClick = {
                vm.backupRequest().also { backupRequest = it; backupLauncher.launch(it.fileName) }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup-create")) {
                Text(stringResource(R.string.backup_create_local))
            } }
            item { Button(onClick = {
                restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup-restore")) {
                Text(stringResource(R.string.backup_restore))
            } }
        }
        item { Button(onClick = {
            vm.workbookRequest().also { workbookRequest = it; workbookLauncher.launch(it.fileName) }
        }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("backup-export-excel")) {
            Text(if (demoMode) stringResource(R.string.backup_export_demo_excel)
                else stringResource(R.string.backup_export_excel))
        } }
        if (!demoMode) {
            item { HorizontalDivider() }
            item { Text(stringResource(R.string.cloud_title), style = MaterialTheme.typography.titleLarge) }
            if (!state.cloud.connected) {
                item { Button({ vm.beginGoogleConnection(launchAuthorization) },
                    enabled = !state.cloudBusy, modifier = Modifier.fillMaxWidth().testTag("cloud-connect")) {
                    Image(painterResource(R.drawable.ic_google_g), contentDescription = null,
                        modifier = Modifier.size(18.dp).testTag("cloud-connect-icon"))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.cloud_connect))
                } }
            } else {
                item { Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(state.cloud.accountDisplay.orEmpty(), style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(state.cloud.folderName.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } }
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Button(vm::backupToGoogleDrive, enabled = !state.cloudBusy,
                        modifier = Modifier.weight(1f).testTag("cloud-backup-now")) {
                        Text(stringResource(R.string.cloud_backup_now))
                    }
                    TextButton(vm::refreshCloudBackups, enabled = !state.cloudBusy,
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cloud_refresh)) }
                } }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.cloud_automatic))
                        if (state.cloud.pauseReason == BackupPauseReason.AFTER_RESTORE) Text(
                            stringResource(R.string.cloud_paused_after_restore),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Switch(state.cloud.automaticEnabled, vm::setAutomatic,
                        enabled = !state.cloudBusy && state.cloud.pauseReason == BackupPauseReason.NONE)
                } }
                if (state.cloud.pauseReason == BackupPauseReason.AFTER_RESTORE) item {
                    Button(vm::resumeAutomatic, enabled = !state.cloudBusy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.cloud_resume_automatic))
                    }
                }
                item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    OutlinedTextField(state.intervalDraft, vm::updateIntervalDraft, Modifier.weight(1f),
                        label = { Text(stringResource(R.string.cloud_interval_hours)) }, singleLine = true)
                    Button(vm::saveInterval, enabled = !state.cloudBusy) {
                        Text(stringResource(R.string.cloud_save_interval))
                    }
                } }
                item { CloudStatus(state.cloud.attemptState, state.cloud.lastSuccessAtUtc,
                    state.cloud.nextDueAtUtc, state.cloud.cleanupIncomplete) }
                item { HorizontalDivider() }
                item { Text(stringResource(R.string.cloud_backups), style = MaterialTheme.typography.titleMedium) }
                if (state.cloud.backups.isEmpty()) item {
                    Text(stringResource(R.string.cloud_no_backups), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(state.cloud.backups.size, key = { state.cloud.backups[it].fileId }) { index ->
                    val value = state.cloud.backups[index]
                    CloudBackupRow(value) { vm.selectCloudBackup(value) }
                    HorizontalDivider()
                }
                state.selectedCloudBackup?.let { value -> item {
                    CloudBackupDetail(value, state.cloudBusy, vm) {
                        vm.cloudDownloadRequest(value).also {
                            cloudDownload = it
                            downloadLauncher.launch(it.fileName)
                        }
                    }
                } }
                item { TextButton({ vm.disconnect(false) }, enabled = !state.cloudBusy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_disconnect)) } }
                item { TextButton({ vm.disconnect(true) }, enabled = !state.cloudBusy,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cloud_revoke)) } }
            }
        }
        if (state.busy || state.cloudBusy) item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CircularProgressIndicator(Modifier.padding(4.dp))
                Text(if (state.busy) stageLabel(state.stage) else stringResource(R.string.cloud_working))
            }
        }
        state.completedFile?.let { result -> item { Text(stringResource(
            if (result.outputVerified) R.string.backup_saved_verified else R.string.backup_saved_unverified,
            result.fileName), color = MaterialTheme.colorScheme.primary) } }
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
            if (state.challenge == null) item { Row(Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TextButton(vm::cancelRestore) { Text(stringResource(R.string.backup_cancel)) }
                Button(vm::requestChallenge, enabled = !state.busy) { Text(stringResource(R.string.backup_continue)) }
            } }
        }
        state.challenge?.let { challenge ->
            item { Text(stringResource(R.string.backup_confirmation_code, challenge.value),
                style = MaterialTheme.typography.titleLarge) }
            item { OutlinedTextField(state.confirmation, vm::updateConfirmation, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.backup_confirmation_input)) }, singleLine = true) }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TextButton(vm::cancelRestore) { Text(stringResource(R.string.backup_cancel)) }
                Button(vm::commitRestore, enabled = !state.busy && state.confirmation.length == 6) {
                    Text(stringResource(R.string.backup_confirm_restore))
                }
            } }
        }
        state.restoreResult?.let { result -> item { Text(stringResource(R.string.backup_restored,
            result.committedAtUtc), color = MaterialTheme.colorScheme.primary) } }
        state.error?.let { item { Text(errorLabel(it), color = MaterialTheme.colorScheme.error) } }
        state.cloudError?.let { item { Text(cloudErrorLabel(it), color = MaterialTheme.colorScheme.error) } }
    }
}

@Composable private fun CloudBackupRow(value: CloudBackupDescriptor, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Space.sm),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(value.snapshotCreatedAtUtc.ifBlank { value.driveCreatedAtUtc }, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(value.fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (value.verificationState == "verified") stringResource(R.string.cloud_verified)
            else stringResource(R.string.cloud_unverified), style = MaterialTheme.typography.labelMedium,
            color = if (value.verificationState == "verified") MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error)
    }
}

@Composable private fun CloudBackupDetail(value: CloudBackupDescriptor, busy: Boolean,
    vm: BackupViewModel, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.cloud_backup_detail), style = MaterialTheme.typography.titleMedium)
            TextButton({ vm.selectCloudBackup(null) }) { Text(stringResource(R.string.backup_cancel)) }
        }
        PreviewRow(stringResource(R.string.backup_created), value.snapshotCreatedAtUtc)
        PreviewRow(stringResource(R.string.cloud_drive_created), value.driveCreatedAtUtc)
        PreviewRow(stringResource(R.string.cloud_file_size), value.byteCount.toString())
        PreviewRow(stringResource(R.string.backup_versions),
            "${value.appVersion} · ${value.internalBuildRevision} · DB ${value.databaseSchemaVersion} · F${value.formatVersion}/D${value.dataSchemaVersion}")
        if (!value.compatible) Text(stringResource(R.string.cloud_incompatible), color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            TextButton(onDownload, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.cloud_download))
            }
            Button({ vm.prepareCloudRestore(value) },
                enabled = !busy && value.compatible && value.verificationState == "verified",
                modifier = Modifier.weight(1f)) { Text(stringResource(R.string.cloud_restore)) }
        }
    }
}

@Composable private fun CloudStatus(status: BackupAttemptState, lastSuccess: String?, next: String?,
    cleanupIncomplete: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PreviewRow(stringResource(R.string.cloud_latest_result), statusLabel(status))
        PreviewRow(stringResource(R.string.cloud_last_success), lastSuccess ?: stringResource(R.string.cloud_never))
        PreviewRow(stringResource(R.string.cloud_next_estimated), next ?: stringResource(R.string.cloud_not_scheduled))
        if (cleanupIncomplete) Text(stringResource(R.string.cloud_cleanup_incomplete),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun PreviewRow(label: String, value: String) {
    Column { Text(label, style = MaterialTheme.typography.labelMedium); Text(value) }
}

@Composable private fun statusLabel(value: BackupAttemptState): String = when (value) {
    BackupAttemptState.IDLE -> stringResource(R.string.cloud_status_idle)
    BackupAttemptState.PREPARING -> stringResource(R.string.cloud_status_preparing)
    BackupAttemptState.WAITING_NETWORK -> stringResource(R.string.cloud_status_waiting_network)
    BackupAttemptState.WAITING_NETWORK_TIME -> stringResource(R.string.cloud_status_waiting_time)
    BackupAttemptState.AUTH_REQUIRED -> stringResource(R.string.cloud_status_auth)
    BackupAttemptState.UPLOADING -> stringResource(R.string.cloud_status_uploading)
    BackupAttemptState.VERIFYING -> stringResource(R.string.cloud_status_verifying)
    BackupAttemptState.SUCCEEDED -> stringResource(R.string.cloud_status_success)
    BackupAttemptState.PREPARATION_FAILED, BackupAttemptState.FAILED -> stringResource(R.string.cloud_status_failed)
    BackupAttemptState.UNKNOWN_RESULT -> stringResource(R.string.cloud_status_unknown)
    BackupAttemptState.CANCELLED -> stringResource(R.string.cloud_status_cancelled)
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

@Composable private fun cloudErrorLabel(error: CloudBackupError): String = when (error) {
    CloudBackupError.NO_NETWORK -> stringResource(R.string.cloud_error_no_network)
    CloudBackupError.NETWORK_TIME_UNAVAILABLE -> stringResource(R.string.cloud_error_network_time)
    CloudBackupError.AUTH_REQUIRED -> stringResource(R.string.cloud_error_auth_required)
    CloudBackupError.AUTH_FAILED -> stringResource(R.string.cloud_error_auth_failed)
    CloudBackupError.DRIVE_PERMISSION -> stringResource(R.string.cloud_error_drive_permission)
    CloudBackupError.DRIVE_FOLDER_NOT_FOUND -> stringResource(R.string.cloud_error_drive_missing)
    CloudBackupError.INVALID_INTERVAL -> stringResource(R.string.cloud_error_interval)
    CloudBackupError.BACKUP_INCOMPATIBLE -> stringResource(R.string.cloud_incompatible)
    CloudBackupError.LOW_STORAGE -> stringResource(R.string.backup_error_space)
    CloudBackupError.RETENTION_CLEANUP_FAILED -> stringResource(R.string.cloud_cleanup_incomplete)
    else -> stringResource(R.string.cloud_error_generic)
}

private fun displayName(context: android.content.Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull() ?: uri.lastPathSegment.orEmpty()
