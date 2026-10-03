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
import androidx.compose.ui.res.stringResource
import dev.valnook.app.R
import androidx.navigation3.runtime.NavKey
import dev.valnook.domain.model.NavigationItemId

/** The surrounding layer is transparent; only the capsule paints a background. */
@Composable
internal fun FloatingNavigationBar(current: NavKey, items: List<NavigationItemId>,
    onSelect: (NavKey) -> Unit, modifier: Modifier) {
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 5.dp,
            modifier = Modifier.fillMaxWidth().testTag("root-capsule")) {
            Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.map { item -> item.rootKey() to stringResource(when (item) {
                    NavigationItemId.ACCOUNTS -> R.string.nav_accounts
                    NavigationItemId.INVESTMENTS -> R.string.nav_investments
                    NavigationItemId.STATISTICS -> R.string.nav_statistics
                    NavigationItemId.SETTINGS -> R.string.nav_settings
                }) }.forEach { (key, label) ->
                    val isSelected = current == key
                    TextButton({ onSelect(key) }, Modifier.weight(1f).heightIn(min = 48.dp)
                        .testTag("nav-${itemForKey(key).name.lowercase()}").semantics { selected = isSelected },
                        shape = RoundedCornerShape(50), colors = ButtonDefaults.textButtonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                        Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

private fun itemForKey(key: NavKey): NavigationItemId = when (key) {
    AccountsKey -> NavigationItemId.ACCOUNTS
    InvestmentsKey -> NavigationItemId.INVESTMENTS
    StatisticsKey -> NavigationItemId.STATISTICS
    else -> NavigationItemId.SETTINGS
}
