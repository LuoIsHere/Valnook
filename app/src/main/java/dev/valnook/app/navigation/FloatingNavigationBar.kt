package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.valnook.app.R
import androidx.navigation3.runtime.NavKey
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.designsystem.GlassBackdrop
import dev.valnook.designsystem.GlassSurface
import dev.valnook.designsystem.CapsuleChoiceRow
import androidx.compose.ui.graphics.Color

/** The surrounding layer is transparent; only the capsule paints a background. */
@Composable
internal fun FloatingNavigationBar(current: NavKey, items: List<NavigationItemId>,
    onSelect: (NavKey) -> Unit, modifier: Modifier, backdrop: GlassBackdrop? = null) {
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        GlassSurface(shape = RoundedCornerShape(50), backdrop = backdrop,
            modifier = Modifier.fillMaxWidth().testTag("root-capsule")) {
            CapsuleChoiceRow(items, itemForKey(current), { onSelect(it.rootKey()) },
                modifier = Modifier.padding(4.dp),
                optionModifier = { Modifier.testTag("nav-${it.name.lowercase()}") },
                controlHeight = 48.dp, containerColor = Color.Transparent,
                indicatorInset = 0.dp, spacing = 4.dp) { item ->
                Text(stringResource(when (item) {
                    NavigationItemId.WALLET -> R.string.nav_wallet
                    NavigationItemId.ACCOUNTS -> R.string.nav_accounts
                    NavigationItemId.INVESTMENTS -> R.string.nav_investments
                    NavigationItemId.STATISTICS -> R.string.nav_statistics
                    NavigationItemId.SETTINGS -> R.string.nav_settings
                }), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun itemForKey(key: NavKey): NavigationItemId = when (key) {
    WalletKey -> NavigationItemId.WALLET
    AccountsKey -> NavigationItemId.ACCOUNTS
    InvestmentsKey -> NavigationItemId.INVESTMENTS
    StatisticsKey -> NavigationItemId.STATISTICS
    else -> NavigationItemId.SETTINGS
}
