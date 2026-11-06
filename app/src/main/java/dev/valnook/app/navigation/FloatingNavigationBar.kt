package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import dev.valnook.app.R
import dev.valnook.designsystem.CapsuleChoiceRow
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons
import dev.valnook.designsystem.GlassBackdrop
import dev.valnook.designsystem.GlassSurface
import dev.valnook.domain.model.NavigationItemId

/**
 * Pages as icons with their names below. The chosen page sits on a capsule that slides between pages.
 * The surrounding layer is transparent; only the outer capsule paints a background.
 */
@Composable
internal fun FloatingNavigationBar(current: NavKey, items: List<NavigationItemId>,
    onSelect: (NavKey) -> Unit, modifier: Modifier, backdrop: GlassBackdrop? = null) {
    val selected = itemForKey(current)
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        GlassSurface(shape = RoundedCornerShape(50), backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().testTag("root-capsule")) {
            CapsuleChoiceRow(items, selected, { onSelect(it.rootKey()) },
                modifier = Modifier.padding(4.dp),
                optionModifier = { Modifier.testTag("nav-${it.name.lowercase()}") },
                controlHeight = 56.dp, containerColor = Color.Transparent,
                indicatorInset = 0.dp, spacing = 4.dp) { item ->
                val style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp,
                    fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal)
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    FaIcon(navigationIcon(item), null, Modifier.size(20.dp))
                    Text(stringResource(navigationLabel(item)), maxLines = 1, style = style,
                        // Tabs share the width equally. A name that does not fit shrinks instead of being cut off.
                        autoSize = TextAutoSize.StepBased(6.sp, style.fontSize, .25.sp))
                }
            }
        }
    }
}

private fun navigationIcon(item: NavigationItemId) = when (item) {
    NavigationItemId.WALLET -> FaIcons.wallet
    NavigationItemId.ACCOUNTS -> FaIcons.buildingColumns
    NavigationItemId.INVESTMENTS -> FaIcons.chartLine
    NavigationItemId.STATISTICS -> FaIcons.chartColumn
    NavigationItemId.SETTINGS -> FaIcons.gear
}

private fun navigationLabel(item: NavigationItemId) = when (item) {
    NavigationItemId.WALLET -> R.string.nav_wallet
    NavigationItemId.ACCOUNTS -> R.string.nav_accounts
    NavigationItemId.INVESTMENTS -> R.string.nav_investments
    NavigationItemId.STATISTICS -> R.string.nav_statistics
    NavigationItemId.SETTINGS -> R.string.nav_settings
}

private fun itemForKey(key: NavKey): NavigationItemId = when (key) {
    WalletKey -> NavigationItemId.WALLET
    AccountsKey -> NavigationItemId.ACCOUNTS
    InvestmentsKey -> NavigationItemId.INVESTMENTS
    StatisticsKey -> NavigationItemId.STATISTICS
    else -> NavigationItemId.SETTINGS
}
