package dev.valnook.feature.settings

import dev.valnook.designsystem.RadioRow
import dev.valnook.designsystem.SwitchRow
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
                SwitchRow(stringResource(R.string.quick_launch), settings.launch, onLaunch, Modifier.testTag("quick-launch"), summary = stringResource(R.string.quick_launch_summary))
                if (settings.launch) {
                    HorizontalDivider(Modifier.padding(horizontal = Space.cardInset))
                    RadioRow(stringResource(R.string.quick_target_manual), settings.target == QuickLaunchTarget.MANUAL, { onTarget(QuickLaunchTarget.MANUAL) }, Modifier.testTag("quick-target-manual"), summary = null, enabled = true,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp))
                    RadioRow(stringResource(R.string.quick_target_ai), settings.target == QuickLaunchTarget.AI, { onTarget(QuickLaunchTarget.AI) }, Modifier.testTag("quick-target-ai"), summary = if (aiChat) null else stringResource(R.string.quick_target_ai_unavailable), enabled = aiChat,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp))
                }
            }
        }
        item { Note(stringResource(R.string.quick_launch_shortcuts), "quick-launch-shortcuts") }
        item {
            GlassCard {
                SwitchRow(stringResource(R.string.quick_share), settings.share, onShare, Modifier.testTag("quick-share"), summary = stringResource(R.string.quick_share_summary))
            }
        }
        item { Note(stringResource(R.string.quick_share_note), "quick-share-note") }
    }
}

/** Small print under a card. */
@Composable
private fun Note(text: String, tag: String) {
    Text(text, Modifier.padding(horizontal = Space.xs).testTag(tag),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

