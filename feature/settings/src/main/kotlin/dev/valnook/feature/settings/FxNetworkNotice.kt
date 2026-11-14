package dev.valnook.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.AnimatedGlassDialog
import dev.valnook.designsystem.GlassCard

/**
 * Shown every time automatic exchange rates are switched on, in onboarding and in Settings, until the user ticks
 * "Don't show again". Wording: keep in line with the privacy policy section on exchange rates (both languages).
 * 文案与隐私政策中汇率一节逐句一致；免责声明加粗。
 */
@Composable
fun FxNetworkNoticeDialog(onCancel: () -> Unit, onAccept: (dontShowAgain: Boolean) -> Unit) {
    dev.valnook.designsystem.PolicyNoticeDialog(stringResource(R.string.settings_fx_notice_title),
        listOf(R.string.settings_fx_notice_source, R.string.settings_fx_notice_when, R.string.settings_fx_notice_sent,
            R.string.settings_fx_notice_not_sent, R.string.settings_fx_notice_storage, R.string.settings_fx_notice_replace)
            .map { stringResource(it) },
        stringResource(R.string.settings_fx_notice_disclaimer), stringResource(R.string.settings_fx_notice_cancel),
        stringResource(R.string.settings_fx_notice_skip), stringResource(R.string.settings_fx_notice_accept), onCancel, onAccept,
        dev.valnook.designsystem.PolicyNoticeTags("fx-notice", "fx-notice-text", "fx-notice-cancel", "fx-notice-skip", "fx-notice-accept"))
}

@Composable
internal fun FxDiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    DiscardDialog(stringResource(R.string.settings_fx_discard_body), stringResource(R.string.settings_fx_discard_confirm),
        "fx-discard-confirm", onKeep, onDiscard)
}

/** Asked when leaving the rates or navigation page with changes that were not saved. */
@Composable
fun UnsavedChangesDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    DiscardDialog(stringResource(R.string.settings_leave_body), stringResource(R.string.settings_leave_confirm),
        "leave-discard", onKeep, onDiscard, keepTag = "leave-keep")
}

@Composable
private fun DiscardDialog(body: String, confirm: String, confirmTag: String, onKeep: () -> Unit, onDiscard: () -> Unit,
    keepTag: String? = null) {
    AnimatedGlassDialog(true, onKeep) {
        GlassCard {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_fx_discard_title), style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onKeep, if (keepTag != null) Modifier.testTag(keepTag) else Modifier) {
                        Text(stringResource(R.string.settings_fx_discard_keep))
                    }
                    TextButton(onDiscard, Modifier.testTag(confirmTag)) { Text(confirm) }
                }
            }
        }
    }
}
