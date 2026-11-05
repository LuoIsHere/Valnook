package dev.valnook.feature.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons

/** The page owns the draft; the root toolbar only renders its current actions. */
data class NavigationToolbarState(val editing: Boolean, val busy: Boolean, val dragging: Boolean,
    val dirty: Boolean, val edit: () -> Unit, val cancel: () -> Unit, val save: () -> Unit)

@Composable
fun NavigationSettingsActions(state: NavigationToolbarState?) {
    if (state == null) return
    if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    if (state.editing) {
        IconButton(state.cancel, enabled = !state.busy && !state.dragging,
            modifier = Modifier.testTag("navigation-cancel")) {
            FaIcon(FaIcons.xmark, stringResource(R.string.settings_cancel))
        }
        IconButton(state.save, enabled = !state.busy && !state.dragging && state.dirty,
            modifier = Modifier.testTag("navigation-save")) {
            FaIcon(FaIcons.check, stringResource(R.string.settings_save))
        }
    } else IconButton(state.edit, modifier = Modifier.testTag("navigation-edit")) {
        FaIcon(FaIcons.pen, stringResource(R.string.settings_edit))
    }
}
