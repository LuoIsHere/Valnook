package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Grouped list: two rows of a small box followed by a line. */
@Composable
fun GroupsIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        val box = size.width * 0.22f
        for (row in listOf(0.32f, 0.68f)) {
            val top = size.height * row - box / 2f
            drawRoundRect(color, Offset(size.width * 0.12f, top), Size(box, box), CornerRadius(stroke), style = Stroke(stroke))
            drawLine(color, Offset(size.width * 0.48f, size.height * row), Offset(size.width * 0.88f, size.height * row),
                stroke, StrokeCap.Round)
        }
    }
}
