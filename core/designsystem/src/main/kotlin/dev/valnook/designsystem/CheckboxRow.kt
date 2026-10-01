package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Keep the checkbox and its full, wrapping label on the same vertical center. */
@Composable
fun CheckboxRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled,
            modifier = Modifier.testTag("checkbox-control-$label"))
        Text(label, Modifier.weight(1f).testTag("checkbox-label-$label"), style = MaterialTheme.typography.bodyLarge)
    }
}
