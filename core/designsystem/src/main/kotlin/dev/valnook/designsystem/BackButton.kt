package dev.valnook.designsystem

import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun BackButton(contentDescription: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.semantics { this.contentDescription = contentDescription }) {
        FaIcon(FaIcons.chevronLeft, tint = MaterialTheme.colorScheme.onSurface)
    }
}
