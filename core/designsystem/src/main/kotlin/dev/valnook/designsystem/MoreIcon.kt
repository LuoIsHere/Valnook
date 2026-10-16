package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp

/** Horizontal three dots for an overflow menu. */
@Composable
fun MoreIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        for (part in listOf(0.25f, 0.5f, 0.75f)) drawCircle(color, 2.dp.toPx(), Offset(size.width * part, size.height / 2f))
    }
}
