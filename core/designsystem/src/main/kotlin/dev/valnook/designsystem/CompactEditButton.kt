package dev.valnook.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun CompactEditButton(label: String, onClick: () -> Unit) {
    EditIcon(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.padding(6.dp))
}

@Composable fun EditIcon(modifier: Modifier = Modifier.size(20.dp)) {
    FaIcon(FaIcons.pen, null, modifier)
}
