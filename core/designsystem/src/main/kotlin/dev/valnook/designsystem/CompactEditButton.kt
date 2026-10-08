package dev.valnook.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun CompactEditButton(label: String, onClick: () -> Unit) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(6.dp)) {
        drawLine(color, Offset(size.width*.22f, size.height*.74f), Offset(size.width*.76f, size.height*.2f), 4.dp.toPx(), StrokeCap.Butt)
        drawLine(color, Offset(size.width*.15f, size.height*.86f), Offset(size.width*.23f, size.height*.64f), 2.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width*.15f, size.height*.86f), Offset(size.width*.37f, size.height*.78f), 2.dp.toPx(), StrokeCap.Round)
    }
}
