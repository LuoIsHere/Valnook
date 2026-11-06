package dev.valnook.feature.settings

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons

/** The page owns the draft; the root toolbar only renders its save. */
data class NavigationToolbarState(val busy: Boolean, val dragging: Boolean, val dirty: Boolean, val save: () -> Unit)

@Composable
fun NavigationSettingsActions(state: NavigationToolbarState?) {
    if (state == null) return
    IconButton(state.save, enabled = !state.busy && !state.dragging && state.dirty,
        modifier = Modifier.testTag("navigation-save")) {
        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else FaIcon(FaIcons.check, stringResource(R.string.settings_save))
    }
}
