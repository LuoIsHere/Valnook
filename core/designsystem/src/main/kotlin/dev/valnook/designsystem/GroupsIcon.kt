package dev.valnook.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun GroupsIcon(modifier: Modifier = Modifier) {
    FaIcon(FaIcons.layers, null, modifier.size(22.dp))
}
