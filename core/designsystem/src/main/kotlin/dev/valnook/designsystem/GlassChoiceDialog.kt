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
        GlassCard(Modifier.testTag(tag), prominent = true) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(Space.cardInset),
                verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.selectableGroup()) {
                    choices.forEach { choice ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(optionTag(choice))
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(choice == selected, enabled = visible && !busy, role = Role.RadioButton) {
                                onSelect(choice)
                            }.padding(horizontal = Space.sm, vertical = Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Column(Modifier.weight(1f)) {
                                Text(label(choice), style = MaterialTheme.typography.bodyLarge)
                                supporting(choice)
                            }
                            RadioButton(choice == selected, onClick = null, enabled = !busy)
                        }
                    }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { HintMessage(it, isError = true) }
                TextButton(onDismiss, Modifier.align(Alignment.End), enabled = !busy) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}
