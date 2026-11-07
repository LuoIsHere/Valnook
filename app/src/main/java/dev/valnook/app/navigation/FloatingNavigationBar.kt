package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import dev.valnook.app.R
import dev.valnook.designsystem.GlassBackdrop
import dev.valnook.designsystem.NavigationCapsule
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.feature.settings.navigationIcon
import dev.valnook.feature.settings.navigationLabel

/**
 * The surrounding layer is transparent; only the capsule paints a background. [fold] narrows it to the current page
 * (0 whole, 1 folded); tapping the folded capsule calls [onExpand].
 */
@Composable
internal fun FloatingNavigationBar(current: NavKey, items: List<NavigationItemId>,
    onSelect: (NavKey) -> Unit, modifier: Modifier, backdrop: GlassBackdrop? = null,
    fold: Float = 0f, onExpand: () -> Unit = {}) {
    val selected = itemForKey(current)
    val expandLabel = stringResource(R.string.nav_expand_bar, navigationLabel(selected))
    Box(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        NavigationCapsule(items, selected, { onSelect(it.rootKey()) }, ::navigationIcon, { navigationLabel(it) },
            Modifier.testTag("root-capsule"), backdrop, optionModifier = { Modifier.testTag("nav-${it.name.lowercase()}") },
            collapse = fold, onExpand = onExpand, expandLabel = expandLabel,
            slots = dev.valnook.domain.model.NavigationConfiguration.MAX_VISIBLE)
    }
}

private fun itemForKey(key: NavKey): NavigationItemId = when (key) {
    WalletKey -> NavigationItemId.WALLET
    AccountsKey -> NavigationItemId.ACCOUNTS
    InvestmentsKey -> NavigationItemId.INVESTMENTS
    StatisticsKey -> NavigationItemId.STATISTICS
    else -> NavigationItemId.SETTINGS
}
