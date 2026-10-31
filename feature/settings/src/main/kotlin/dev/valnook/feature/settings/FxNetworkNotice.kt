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
    var skip by remember { mutableStateOf(false) }
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .8f
    AnimatedGlassDialog(true, onCancel) {
        GlassCard {
            Column(Modifier.heightIn(max = maximumHeight).verticalScroll(rememberScrollState()).padding(22.dp)
                .testTag("fx-notice"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_fx_notice_title), style = MaterialTheme.typography.titleLarge)
                listOf(R.string.settings_fx_notice_source, R.string.settings_fx_notice_when, R.string.settings_fx_notice_sent,
                    R.string.settings_fx_notice_not_sent, R.string.settings_fx_notice_storage, R.string.settings_fx_notice_replace)
                    .forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                Text(stringResource(R.string.settings_fx_notice_disclaimer), style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onCancel, Modifier.testTag("fx-notice-cancel")) { Text(stringResource(R.string.settings_fx_notice_cancel)) }
                    Spacer(Modifier.weight(1f))
                    Row(Modifier.clip(RoundedCornerShape(8.dp)).toggleable(skip, onValueChange = { skip = it })
                        .testTag("fx-notice-skip"), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(skip, null)
                        Text(stringResource(R.string.settings_fx_notice_skip), style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton({ onAccept(skip) }, Modifier.testTag("fx-notice-accept")) {
                        Text(stringResource(R.string.settings_fx_notice_accept))
                    }
                }
            }
        }
    }
}

@Composable
internal fun FxDiscardDialog(onKeep: () -> Unit, onDiscard: () -> Unit) {
    AnimatedGlassDialog(true, onKeep) {
        GlassCard {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_fx_discard_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.settings_fx_discard_body), style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onKeep) { Text(stringResource(R.string.settings_fx_discard_keep)) }
                    TextButton(onDiscard, Modifier.testTag("fx-discard-confirm")) {
                        Text(stringResource(R.string.settings_fx_discard_confirm))
                    }
                }
            }
        }
    }
}
