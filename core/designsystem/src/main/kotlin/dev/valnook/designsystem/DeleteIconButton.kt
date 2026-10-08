package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun DeleteIconButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
    IconButton(onClick, modifier.semantics { contentDescription = label }, enabled = enabled) {
        Canvas(Modifier.size(20.dp)) {
            val w = size.width; val h = size.height; val stroke = 1.7.dp.toPx()
            drawLine(color, Offset(w*.12f,h*.25f), Offset(w*.88f,h*.25f), stroke)
            drawRect(color, Offset(w*.24f,h*.25f), androidx.compose.ui.geometry.Size(w*.52f,h*.62f), style = Stroke(stroke))
            drawLine(color, Offset(w*.38f,h*.1f), Offset(w*.62f,h*.1f), stroke)
            drawLine(color, Offset(w*.42f,h*.4f), Offset(w*.42f,h*.72f), stroke)
            drawLine(color, Offset(w*.58f,h*.4f), Offset(w*.58f,h*.72f), stroke)
        }
    }
}
