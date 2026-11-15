package dev.valnook.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/** The inner padding and gaps every dialog of the app shares. */
object DialogDefaults {
    val Padding = 20.dp
    val Gap = 12.dp
}

/**
 * The dialog of the app: a frosted card with [title], [text] and the buttons at its end, [dismissButton] before
 * [confirmButton]. Takes the same parameters as Material's AlertDialog, so either reads the same. A test tag on
 * [modifier] sits on the card.
 */
@Composable
fun ValnookDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null, properties: DialogProperties = DialogProperties()) {
    AnimatedGlassDialog(true, onDismissRequest, properties) {
        DialogCard(modifier, title, buttons = {
            dismissButton?.invoke()
            confirmButton()
        }) {
            if (text != null) CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
            }
        }
    }
}

/**
 * The card inside a dialog, for dialogs that build their own content: [title] in the dialog style, [content], and
 * [buttons] in a row at the end. Every dialog uses it, so padding, title and buttons line up across the app.
 */
@Composable
fun DialogCard(modifier: Modifier = Modifier, title: (@Composable () -> Unit)? = null,
    buttons: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier, prominent = true) {
        Column(Modifier.fillMaxWidth().padding(DialogDefaults.Padding),
            verticalArrangement = Arrangement.spacedBy(DialogDefaults.Gap)) {
            if (title != null) ProvideTextStyle(MaterialTheme.typography.titleLarge) { title() }
            content()
            if (buttons != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically) { buttons() }
        }
    }
}

/**
 * The top line of a bottom sheet: [leading] (a back or collapse button), [title], and [actions] at the end, such as
 * "Done" or "Cancel" and "Confirm". Every sheet of the app starts with it.
 */
@Composable
fun SheetHeader(title: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = if (leading == null) 4.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        leading?.invoke()
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** [DialogCard] with a plain [title]. */
@Composable
fun DialogCard(title: String?, modifier: Modifier = Modifier, buttons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) =
    DialogCard(modifier, title?.let { { androidx.compose.material3.Text(it) } }, buttons, content)
