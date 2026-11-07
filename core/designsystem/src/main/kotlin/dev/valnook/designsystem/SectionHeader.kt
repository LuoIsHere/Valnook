package dev.valnook.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * A card's small title with a link to a related list on its right, as "已清仓 ›". Kept low, so the title reads with
 * the card under it rather than floating between cards.
 */
@Composable
fun SectionHeader(title: String, actionLabel: String?, onAction: () -> Unit, modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null) Row(actionModifier.heightIn(min = 32.dp).clip(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(actionLabel, style = MaterialTheme.typography.bodyMedium)
            RowChevron()
        }
    }
}
