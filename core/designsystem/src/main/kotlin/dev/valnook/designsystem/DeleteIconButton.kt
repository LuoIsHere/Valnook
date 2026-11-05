package dev.valnook.designsystem

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*

@Composable fun DeleteIconButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    IconButton(onClick, modifier.semantics { contentDescription = label }, enabled = enabled) {
        FaIcon(FaIcons.trash, tint = color)
    }
}
