package dev.valnook.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The title bar's save button of every form: a check mark, or a small spinner while saving. Read as [label]. */
@Composable fun ToolbarSaveAction(label: String, busy: Boolean, enabled: Boolean, onSave: () -> Unit,
    modifier: Modifier = Modifier) {
    IconButton(onSave, enabled = enabled, modifier = modifier.semantics { contentDescription = label }) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else FaIcon(FaIcons.check, modifier = Modifier.size(22.dp))
    }
}
