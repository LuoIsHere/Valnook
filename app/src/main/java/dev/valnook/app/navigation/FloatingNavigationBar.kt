package dev.valnook.app.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey

/** The surrounding layer is transparent; only the capsule paints a background. */
@Composable
internal fun FloatingNavigationBar(current: NavKey, onSelect: (NavKey) -> Unit, modifier: Modifier) {
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 5.dp,
            modifier = Modifier.fillMaxWidth().testTag("root-capsule")) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(AccountsKey to "账户", InvestmentsKey to "投资", SettingsKey to "设置").forEach { (key, label) ->
                    val isSelected = current == key
                    TextButton({ onSelect(key) }, Modifier.weight(1f).heightIn(min = 48.dp).semantics { selected = isSelected },
                        shape = RoundedCornerShape(50), colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                        Text(label)
                    }
                }
            }
        }
    }
}
