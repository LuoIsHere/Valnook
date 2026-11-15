package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R

/** Shared single-choice interaction for appearance settings. Persistence stays with the caller. */
@Composable
fun <T> GlassChoiceDialog(visible: Boolean, title: String, choices: List<T>, selected: T,
    label: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit,
    tag: String, optionTag: (T) -> String, busy: Boolean = false, error: String? = null,
    supporting: @Composable (T) -> Unit = {}) {
    AnimatedGlassDialog(visible, { if (!busy) onDismiss() }) {
        DialogCard(title, Modifier.testTag(tag), buttons = {
            TextButton(onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) }
        }) {
            // Only the choices scroll; the title and Cancel stay in view.
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).selectableGroup()) {
                choices.forEach { choice ->
                    Column {
                        RadioRow(label(choice), choice == selected, { onSelect(choice) },
                            Modifier.testTag(optionTag(choice)), enabled = visible && !busy)
                        Box(Modifier.padding(start = 56.dp)) { supporting(choice) }
                    }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { HintMessage(it, isError = true) }
        }
    }
}
