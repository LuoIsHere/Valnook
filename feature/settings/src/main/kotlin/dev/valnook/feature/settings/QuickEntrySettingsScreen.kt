package dev.valnook.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.LocalPageBottomSpace
import dev.valnook.designsystem.Space

/** Where the app opens when started afresh with "open straight to a bill" on. */
enum class QuickLaunchTarget { MANUAL, AI }

/**
 * Quick bookkeeping, kept on this device only: [launch] opens a bill page when the app starts afresh from its icon,
 * on [target]; [share] puts "Valnook bill" in the system share sheet.
 */
data class QuickEntrySettings(val launch: Boolean = false, val target: QuickLaunchTarget = QuickLaunchTarget.MANUAL,
    val share: Boolean = true)

/** The line under "Quick bookkeeping" in Settings. */
@Composable
fun quickEntrySummary(settings: QuickEntrySettings): String {
    val launch = if (!settings.launch) stringResource(R.string.quick_summary_launch_off)
        else stringResource(R.string.quick_summary_launch, targetName(settings.target))
    val share = stringResource(if (settings.share) R.string.quick_summary_share_on else R.string.quick_summary_share_off)
    return "$launch · $share"
}

@Composable
private fun targetName(target: QuickLaunchTarget) = stringResource(when (target) {
    QuickLaunchTarget.MANUAL -> R.string.quick_target_manual
    QuickLaunchTarget.AI -> R.string.quick_target_ai
})

/**
 * Settings → Quick bookkeeping. [aiChat] is whether the AI chat can be opened now; without it the AI choice waits,
 * and a start set to it opens the bill page instead.
 */
@Composable
fun QuickEntrySettingsScreen(settings: QuickEntrySettings, aiChat: Boolean, onLaunch: (Boolean) -> Unit,
    onTarget: (QuickLaunchTarget) -> Unit, onShare: (Boolean) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("quick-entry"),
        contentPadding = PaddingValues(start = Space.md, end = Space.md, top = Space.sm, bottom = Space.md + LocalPageBottomSpace.current),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            GlassCard {
                SwitchLine(stringResource(R.string.quick_launch), stringResource(R.string.quick_launch_summary),
                    settings.launch, "quick-launch", onLaunch)
                if (settings.launch) {
                    HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                    TargetLine(stringResource(R.string.quick_target_manual), null, settings.target == QuickLaunchTarget.MANUAL,
                        true, "quick-target-manual") { onTarget(QuickLaunchTarget.MANUAL) }
                    TargetLine(stringResource(R.string.quick_target_ai),
                        if (aiChat) null else stringResource(R.string.quick_target_ai_unavailable),
                        settings.target == QuickLaunchTarget.AI, aiChat, "quick-target-ai") { onTarget(QuickLaunchTarget.AI) }
                }
            }
        }
        item {
            GlassCard {
                SwitchLine(stringResource(R.string.quick_share), stringResource(R.string.quick_share_summary),
                    settings.share, "quick-share", onShare)
            }
        }
        item {
            Text(stringResource(R.string.quick_share_note), Modifier.padding(horizontal = Space.xs).testTag("quick-share-note"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A line that toggles as a whole, as on the exchange rate page. */
@Composable
private fun SwitchLine(title: String, summary: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(tag)
        .toggleable(checked, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = Space.md), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null)
    }
}

@Composable
private fun TargetLine(title: String, summary: String?, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Column(Modifier.weight(1f).padding(start = Space.sm), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
