package dev.valnook.feature.settings

import dev.valnook.designsystem.ToolbarSaveAction
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

/**
 * The rates page owns the draft; the root toolbar only renders its save, and asks before leaving while [dirty].
 * Automatic rates have nothing to save.
 */
data class FxToolbarState(val busy: Boolean, val dirty: Boolean, val canSave: Boolean, val save: () -> Unit)

@Composable
fun FxSettingsActions(state: FxToolbarState?) {
    if (state == null) return
    ToolbarSaveAction(stringResource(R.string.settings_save), state.busy, !state.busy && state.canSave, state.save,
        Modifier.testTag("fx-save"))
}
