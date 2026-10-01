package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Flexible columns keep long values and large text readable without clipping. */
@Composable fun DetailRow(label:String,value:String,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().testTag("detail-$label").semantics(mergeDescendants=true){}
        .padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(label,modifier=Modifier.weight(0.34f),style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,modifier=Modifier.weight(0.66f),style=MaterialTheme.typography.bodyLarge)
    }
}
