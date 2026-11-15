package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** The test tags of a [PolicyNoticeDialog]; each page keeps its own. */
data class PolicyNoticeTags(val dialog: String, val text: String? = null, val cancel: String? = null,
    val skip: String? = null, val confirm: String? = null)

/**
 * The notice shown before turning on something that sends or keeps data (automatic rates, card details, AI): a
 * title, [paragraphs] worded like the privacy policy, an optional [disclaimer] in bold, then Cancel, "Don't show
 * again" (when [skipLabel] is given) and the confirm button. Only the text scrolls, so on a short screen or with large
 * fonts the title and the buttons stay in view.
 */
@Composable
fun PolicyNoticeDialog(
    title: String,
    paragraphs: List<String>,
    disclaimer: String?,
    cancelLabel: String,
    skipLabel: String?,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    tags: PolicyNoticeTags,
    properties: DialogProperties = DialogProperties(),
) {
    var skip by remember { mutableStateOf(false) }
    val maximumHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .8f
    AnimatedGlassDialog(true, onCancel, properties = properties) {
        DialogCard(title, Modifier.heightIn(max = maximumHeight).testTag(tags.dialog)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                .then(tags.text?.let { Modifier.testTag(it) } ?: Modifier), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                disclaimer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold) }
            }
            // Cancel sits at the start, apart from "Don't show again" and the confirm button.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onCancel, tags.cancel?.let { Modifier.testTag(it) } ?: Modifier) { Text(cancelLabel) }
                Spacer(Modifier.weight(1f))
                if (skipLabel != null) Row(Modifier.clip(RoundedCornerShape(8.dp)).toggleable(skip, onValueChange = { skip = it })
                    .then(tags.skip?.let { Modifier.testTag(it) } ?: Modifier), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(skip, null)
                    Text(skipLabel, style = MaterialTheme.typography.labelMedium)
                }
                TextButton({ onConfirm(skip) }, tags.confirm?.let { Modifier.testTag(it) } ?: Modifier) { Text(confirmLabel) }
            }
        }
    }
}
