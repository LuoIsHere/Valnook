package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

@Composable
fun MenuIcon() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(24.dp)) {
        for (part in listOf(0.25f, 0.5f, 0.75f)) {
            drawLine(color, Offset(size.width * 0.12f, size.height * part),
                Offset(size.width * 0.88f, size.height * part), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}
