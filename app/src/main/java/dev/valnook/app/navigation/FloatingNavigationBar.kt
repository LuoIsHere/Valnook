package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.valnook.designsystem.FaIcon
import dev.valnook.designsystem.FaIcons
import dev.valnook.designsystem.GlassSurface
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
 * (0 whole, 1 folded), read only while laying out and drawing; tapping the folded capsule calls [onExpand].
 */
@Composable
internal fun FloatingNavigationBar(current: NavKey, items: List<NavigationItemId>,
    onSelect: (NavKey) -> Unit, modifier: Modifier, backdrop: GlassBackdrop? = null,
    fold: () -> Float = { 0f }, onExpand: () -> Unit = {}, onAddBill: (() -> Unit)? = null) {
    val selected = itemForKey(current)
    val expandLabel = stringResource(R.string.nav_expand_bar, navigationLabel(selected))
    Column(modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Above the bar at the right, moving with it when it folds; part of the overlay, so pages leave room for it.
        if (onAddBill != null) AddBillButton(onAddBill, backdrop)
        NavigationCapsule(items, selected, { onSelect(it.rootKey()) }, ::navigationIcon, { navigationLabel(it) },
            Modifier.testTag("root-capsule"), backdrop, optionModifier = { Modifier.testTag("nav-${it.name.lowercase()}") },
            collapse = fold, onExpand = onExpand, expandLabel = expandLabel,
            slots = dev.valnook.domain.model.NavigationConfiguration.MAX_VISIBLE)
    }
}

@Composable private fun AddBillButton(onClick: () -> Unit, backdrop: GlassBackdrop?) {
    val label = stringResource(R.string.nav_add_bill)
    GlassSurface(Modifier.size(56.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }.testTag("add-bill"), shape = CircleShape, backdrop = backdrop) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            FaIcon(FaIcons.plus, null, Modifier.size(24.dp), MaterialTheme.colorScheme.onSurface)
        }
    }
}

private fun itemForKey(key: NavKey): NavigationItemId = when (key) {
    BillsKey -> NavigationItemId.BILLS
    WalletKey -> NavigationItemId.WALLET
    AccountsKey -> NavigationItemId.ACCOUNTS
    InvestmentsKey -> NavigationItemId.INVESTMENTS
    StatisticsKey -> NavigationItemId.STATISTICS
    else -> NavigationItemId.SETTINGS
}
