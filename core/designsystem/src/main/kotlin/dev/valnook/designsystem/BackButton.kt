package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun BackButton(contentDescription: String, onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.onSurface
    IconButton(onClick, Modifier.semantics { this.contentDescription = contentDescription }) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(color, Offset(size.width * .65f, size.height * .2f),
                Offset(size.width * .35f, size.height * .5f), stroke, StrokeCap.Round)
            drawLine(color, Offset(size.width * .35f, size.height * .5f),
                Offset(size.width * .65f, size.height * .8f), stroke, StrokeCap.Round)
        }
    }
}
