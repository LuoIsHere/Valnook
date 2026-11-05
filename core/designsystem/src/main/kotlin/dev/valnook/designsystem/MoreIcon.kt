package dev.valnook.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Horizontal three dots for an overflow menu. */
@Composable
fun MoreIcon(modifier: Modifier = Modifier) {
    FaIcon(FaIcons.ellipsis, null, modifier.size(22.dp))
}
