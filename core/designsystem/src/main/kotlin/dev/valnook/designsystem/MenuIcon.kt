package dev.valnook.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MenuIcon() {
    FaIcon(FaIcons.bars, null, Modifier.size(22.dp), MaterialTheme.colorScheme.onSurface)
}
