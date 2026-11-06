package dev.valnook.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.valnook.designsystem.FaIcons
import dev.valnook.domain.model.NavigationItemId

/** Each page's icon in the bottom navigation, shared by the bar and the navigation settings preview. */
fun navigationIcon(item: NavigationItemId): Int = when (item) {
    NavigationItemId.WALLET -> FaIcons.wallet
    NavigationItemId.ACCOUNTS -> FaIcons.buildingColumns
    NavigationItemId.INVESTMENTS -> FaIcons.chartLine
    NavigationItemId.STATISTICS -> FaIcons.chartColumn
    NavigationItemId.SETTINGS -> FaIcons.gear
}

/** Each page's name as the navigation shows it. */
@Composable
fun navigationLabel(value: NavigationItemId): String = stringResource(when (value) {
    NavigationItemId.WALLET -> R.string.settings_nav_wallet
    NavigationItemId.ACCOUNTS -> R.string.settings_nav_accounts
    NavigationItemId.INVESTMENTS -> R.string.settings_nav_investments
    NavigationItemId.STATISTICS -> R.string.settings_nav_statistics
    NavigationItemId.SETTINGS -> R.string.settings_nav_settings
})
