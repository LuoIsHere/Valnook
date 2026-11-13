package dev.valnook.feature.bills

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.valnook.designsystem.*
import dev.valnook.domain.repository.BillAccountChoice

private const val SHEET_MIN = 0.52f
private const val SHEET_MAX = 0.92f

/** The choice the picker offers besides the user's own sub-accounts. */
enum class PickerOption { NO_ACCOUNT, EXTERNAL }

/**
 * Picks a bill's sub-account in a sheet over the lower half of the screen: the pinned sub-accounts (or, with none
 * pinned, all of them). "All accounts" at its top right pulls it up to nearly the whole screen with every account,
 * a search and a star on each to pin it. [option] is "no account" for expense and income, "outside account" for a
 * transfer's side, or none (refunds and reimbursements only go to the user's own sub-accounts).
 */
@Composable fun BillAccountPicker(accounts: List<BillAccountChoice>, selected: Long?, option: PickerOption?,
    optionSelected: Boolean, onPick: (Long?) -> Unit, onFavorites: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val favorites = accounts.filter { it.favorite }.sortedBy { it.favoriteOrder }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val screen = LocalConfiguration.current.screenHeightDp.dp
    val screenPx = with(LocalDensity.current) { screen.toPx() }
    // The sheet's share of the screen: half to start, pulled up by hand (or by scrolling a long list), and all the
    // way for every account.
    var fraction by rememberSaveable { mutableFloatStateOf(if (expanded) SHEET_MAX else SHEET_MIN) }
    val scope = rememberCoroutineScope()
    fun animateTo(target: Float) { scope.launch { animate(fraction, target) { value, _ -> fraction = value } } }
    /** Moves the sheet by a finger movement of [dy] pixels (up is negative); returns the part it used. */
    fun dragBy(dy: Float): Float {
        val next = (fraction - dy / screenPx).coerceIn(SHEET_MIN, SHEET_MAX)
        val used = (fraction - next) * screenPx
        fraction = next
        return used
    }
    val pull = remember(screenPx) { object : NestedScrollConnection {
        // A list scrolled up first lifts the sheet; one pulled down at its top lowers it again.
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (available.y < 0 && fraction < SHEET_MAX) Offset(0f, dragBy(available.y)) else Offset.Zero
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (available.y > 0 && fraction > SHEET_MIN) Offset(0f, dragBy(available.y)) else Offset.Zero
    } }
    val height = screen * fraction
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().clickable(MutableInteractionSource(), null, onClick = onDismiss)) {
            // Opaque: nothing blurs behind a dialog, so the page under it would show through glass.
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(height)
                .clickable(MutableInteractionSource(), null) {}.testTag("bill-account-picker"),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).nestedScroll(pull)
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    // The handle: drag it to pull the sheet up or down.
                    Box(Modifier.fillMaxWidth().height(24.dp)
                        .draggable(rememberDraggableState { dragBy(it) }, Orientation.Vertical).testTag("bill-account-sheet-handle"),
                        contentAlignment = Alignment.Center) {
                        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)))
                    }
                    Row(Modifier.draggable(rememberDraggableState { dragBy(it) }, Orientation.Vertical),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (expanded) IconButton({ expanded = false; query = ""; animateTo(SHEET_MIN) }, Modifier.testTag("bill-accounts-collapse")) {
                            FaIcon(FaIcons.chevronDown, stringResource(R.string.bill_favorites), Modifier.size(16.dp))
                        }
                        Text(stringResource(when {
                            arranging -> R.string.bill_arrange_favorites
                            expanded -> R.string.bill_all_accounts
                            else -> R.string.bill_choose_account
                        }), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        if (!expanded && favorites.size > 1) TextButton({ arranging = !arranging }, Modifier.testTag("bill-arrange-favorites")) {
                            Text(stringResource(if (arranging) R.string.bill_done else R.string.bill_arrange))
                        }
                        if (!expanded && !arranging) TextButton({ expanded = true; animateTo(SHEET_MAX) }, Modifier.testTag("bill-all-accounts")) {
                            Text(stringResource(R.string.bill_all_accounts))
                        }
                    }
                    when {
                        arranging -> {
                            val byKey = favorites.associateBy { it.cashAccountId.toString() }
                            ReorderList(favorites.map { ReorderItem(it.cashAccountId.toString(), it.label(), it.currency.code) },
                                { keys -> onFavorites(keys.map(String::toLong)) }, stringResource(R.string.bill_arrange),
                                stringResource(R.string.bill_move_up), stringResource(R.string.bill_move_down), Modifier.weight(1f),
                                leadingContent = { item -> byKey[item.key]?.let { AccountAvatar(it.iconSymbol, it.iconImageKey, size = 36.dp) } },
                                rowColor = Color.Transparent, draggedColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                dividerInset = 56.dp)
                        }
                        expanded -> {
                            GlassTextField(query, { query = it }, stringResource(R.string.bill_search_accounts),
                                Modifier.fillMaxWidth().testTag("bill-account-search"))
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (query.isBlank() && option != null) item { OptionRow(option, optionSelected) { onPick(null) } }
                                allAccounts(accounts.filter { query.isBlank() || it.label().contains(query.trim(), ignoreCase = true) },
                                    selected.takeIf { !optionSelected }, favorites, onPick, onFavorites, stars = true)
                            }
                        }
                        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (option != null) item { OptionRow(option, optionSelected) { onPick(null) } }
                            if (favorites.isEmpty()) {
                                item { HintMessage(stringResource(R.string.bill_favorites_hint)) }
                                allAccounts(accounts, selected.takeIf { !optionSelected }, favorites, onPick, onFavorites, stars = true)
                            } else items(favorites, key = { it.cashAccountId }) { choice ->
                                AccountRow(choice, choice.cashAccountId == selected && !optionSelected, { onPick(choice.cashAccountId) }) {}
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Every sub-account under its account's name; those kept off the accounts page come last in their group. */
private fun LazyListScope.allAccounts(accounts: List<BillAccountChoice>, selected: Long?, favorites: List<BillAccountChoice>,
    onPick: (Long?) -> Unit, onFavorites: (List<Long>) -> Unit, stars: Boolean) {
    val sorted = accounts.sortedWith(compareBy({ it.accountOrder }, { it.accountId }, { !it.shownOnAccountsPage }, { it.cashOrder }))
    var lastAccount: Long? = null
    sorted.forEach { choice ->
        if (choice.accountId != lastAccount) {
            lastAccount = choice.accountId
            item(key = "header-${choice.accountId}") {
                Text(choice.accountName, Modifier.padding(start = 4.dp, top = Space.md, bottom = 2.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item(key = choice.cashAccountId) {
            AccountRow(choice, choice.cashAccountId == selected, { onPick(choice.cashAccountId) }) {
                if (stars) {
                    val pinned = choice.favorite
                    IconButton({
                        onFavorites(if (pinned) favorites.map { it.cashAccountId } - choice.cashAccountId
                            else favorites.map { it.cashAccountId } + choice.cashAccountId)
                    }, Modifier.semantics { stateDescription = if (pinned) "★" else "☆" }.testTag("bill-favorite-${choice.cashAccountId}")) {
                        Text(if (pinned) "★" else "☆", style = MaterialTheme.typography.titleLarge,
                            color = if (pinned) billColor("amber") else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** "No account" for expense and income, "outside account" for a transfer's side. */
@Composable private fun OptionRow(option: PickerOption, selected: Boolean, onClick: () -> Unit) {
    val external = option == PickerOption.EXTERNAL
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }.padding(horizontal = 8.dp, vertical = 10.dp)
        .testTag(if (external) "bill-external" else "bill-no-account"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        CategoryIcon(if (external) "globe" else "circle-question", "grey", size = 36.dp, selected = selected)
        Column(Modifier.weight(1f)) {
            Text(stringResource(if (external) R.string.bill_external_account else R.string.bill_no_account))
            Text(stringResource(if (external) R.string.bill_external_hint else R.string.bill_no_account_hint),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) FaIcon(FaIcons.check, null, Modifier.size(14.dp))
    }
}

/** A sub-account with its currency and balance, or what is left to spend on a credit one. */
@Composable private fun AccountRow(choice: BillAccountChoice, selected: Boolean, onClick: () -> Unit,
    trailing: @Composable () -> Unit) {
    val digits = choice.currency.fraction_digits
    val amount = if (choice.isCredit) choice.availableCreditMinor?.let {
        stringResource(R.string.bill_available_credit, plainAmount(it, digits))
    } ?: stringResource(R.string.bill_credit_no_limit) else plainAmount(choice.balanceMinor, digits)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }.padding(horizontal = 8.dp, vertical = 8.dp).testTag("bill-account-${choice.cashAccountId}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        AccountAvatar(choice.iconSymbol, choice.iconImageKey, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(choice.label(), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${choice.currency.code} · $amount", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) FaIcon(FaIcons.check, null, Modifier.size(14.dp))
        trailing()
    }
}
