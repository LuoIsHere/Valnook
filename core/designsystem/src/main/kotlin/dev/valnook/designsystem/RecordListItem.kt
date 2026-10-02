package dev.valnook.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/** Compact record row with a shared divider treatment across ledger features. */
@Composable fun RecordListItem(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null,
    showDivider: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        val interaction = if (onClick == null) Modifier else
            Modifier.clickable(role = Role.Button, onClick = onClick)
        Column(Modifier.fillMaxWidth().then(interaction).padding(vertical = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs), content = content)
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
