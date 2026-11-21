package dev.valnook.feature.backup

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.AnimatedGlassDialog
import dev.valnook.designsystem.DialogCard
import dev.valnook.designsystem.Space
import dev.valnook.domain.cloud.CloudImageProblem
import dev.valnook.domain.cloud.CloudImageStatus

/** Picture transfers above this many first ask to keep the screen on and the app in front. */
internal const val FOREGROUND_REMINDER_ABOVE = 20

/**
 * What OneDrive backup sends and where: shown before connecting and, for a connection made before pictures were
 * backed up, before the first picture goes up. [connecting] asks to go on; otherwise it is only read.
 */
@Composable internal fun OneDriveNoticeDialog(connecting: Boolean, onCancel: () -> Unit, onAccept: () -> Unit) {
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .8f
    AnimatedGlassDialog(true, onCancel) {
        DialogCard(stringResource(R.string.cloud_notice_title), Modifier.heightIn(max = maximumHeight).testTag("cloud-notice"), buttons = {
            if (connecting) TextButton(onCancel, Modifier.testTag("cloud-notice-cancel")) { Text(stringResource(R.string.backup_cancel)) }
            TextButton(onAccept, Modifier.testTag("cloud-notice-accept")) {
                Text(stringResource(if (connecting) R.string.cloud_notice_connect else R.string.cloud_notice_done))
            }
        }) {
            // Only the text scrolls: on a short screen or with large fonts the title and the buttons stay in view.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(R.string.cloud_notice_what, R.string.cloud_notice_not_sent, R.string.cloud_notice_where,
                    R.string.cloud_notice_access, R.string.cloud_notice_pictures, R.string.cloud_notice_keep,
                    R.string.cloud_notice_disconnect)
                    .forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

/** Before many pictures move: the app has no background service, so the screen must stay on with it in front. */
@Composable internal fun ForegroundReminderDialog(count: Int, fetching: Boolean, onCancel: () -> Unit,
    onContinue: (dontRemind: Boolean) -> Unit) {
    var skip by remember { mutableStateOf(false) }
    AnimatedGlassDialog(true, onCancel) {
        DialogCard(stringResource(R.string.cloud_foreground_title), Modifier.testTag("cloud-foreground-reminder")) {
            Text(stringResource(if (fetching) R.string.cloud_foreground_fetch else R.string.cloud_foreground_upload, count),
                style = MaterialTheme.typography.bodyMedium)
            // Cancel sits at the start, apart from "Don't remind me" and Continue.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onCancel, Modifier.testTag("cloud-foreground-cancel")) { Text(stringResource(R.string.backup_cancel)) }
                Spacer(Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(8.dp)).toggleable(skip, onValueChange = { skip = it })
                    .testTag("cloud-foreground-skip"), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(skip, null)
                    Text(stringResource(R.string.cloud_foreground_skip), style = MaterialTheme.typography.labelMedium)
                }
                TextButton({ onContinue(skip) }, Modifier.testTag("cloud-foreground-continue")) {
                    Text(stringResource(R.string.cloud_foreground_continue))
                }
            }
        }
    }
}

/** Disconnecting while pictures are only in OneDrive: they could not be fetched from the app afterwards. */
@Composable internal fun DisconnectPicturesDialog(count: Int, onFetch: () -> Unit, onDisconnect: () -> Unit, onCancel: () -> Unit) {
    AnimatedGlassDialog(true, onCancel) {
        DialogCard(stringResource(R.string.cloud_disconnect_title), Modifier.testTag("cloud-disconnect-pictures")) {
            Text(stringResource(R.string.cloud_disconnect_pictures, count), style = MaterialTheme.typography.bodyMedium)
            // Three long choices: stacked at the end rather than in one row.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                TextButton(onFetch, Modifier.testTag("cloud-disconnect-fetch")) { Text(stringResource(R.string.cloud_disconnect_fetch_first)) }
                TextButton(onDisconnect, Modifier.testTag("cloud-disconnect-anyway")) { Text(stringResource(R.string.cloud_disconnect_anyway)) }
                TextButton(onCancel) { Text(stringResource(R.string.backup_cancel)) }
            }
        }
    }
}

/** Bill pictures and OneDrive on the backup page: what moves now, what went wrong, what waits. */
@Composable internal fun CloudImagesSection(status: CloudImageStatus, busy: Boolean, onNotice: () -> Unit, onStop: () -> Unit,
    onFetchAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("cloud-images"), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        @Composable fun line(text: String, tag: String, error: Boolean = false) =
            Text(text, Modifier.testTag(tag), style = MaterialTheme.typography.bodyMedium,
                color = if (error) colors.error else colors.onSurfaceVariant)
        status.uploading?.let { (done, total) -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { line(stringResource(R.string.cloud_images_uploading, done, total), "cloud-images-uploading") }
            TextButton(onStop, Modifier.testTag("cloud-images-stop")) { Text(stringResource(R.string.cloud_images_stop)) }
        } }
        status.fetching?.let { (done, total) -> Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { line(stringResource(R.string.cloud_images_fetching, done, total), "cloud-images-fetching") }
            TextButton(onStop, Modifier.testTag("cloud-images-stop")) { Text(stringResource(R.string.cloud_images_stop)) }
        } }
        if (status.noticeNeeded && status.onDevice > 0) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { line(stringResource(R.string.cloud_images_notice_needed), "cloud-images-notice-needed") }
            TextButton(onNotice, Modifier.testTag("cloud-images-read-notice")) { Text(stringResource(R.string.cloud_images_read_notice)) }
        }
        if (status.problem == CloudImageProblem.FOLDER_CONFLICT) line(stringResource(R.string.cloud_images_conflict), "cloud-images-conflict", error = true)
        else if (!status.noticeNeeded && status.uploading == null && status.notUploaded > 0)
            line(stringResource(R.string.cloud_images_pending, status.notUploaded), "cloud-images-pending")
        if (status.onlyInCloud > 0 && status.fetching == null) Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { line(stringResource(R.string.cloud_images_only_cloud, status.onlyInCloud), "cloud-images-only-cloud") }
            TextButton(onFetchAll, enabled = !busy, modifier = Modifier.testTag("cloud-images-fetch-all")) {
                Text(stringResource(R.string.cloud_images_fetch_all))
            }
        }
        if (status.missing > 0) line(stringResource(R.string.cloud_images_missing, status.missing), "cloud-images-missing")
    }
}
