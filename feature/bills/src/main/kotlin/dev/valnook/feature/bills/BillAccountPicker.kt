package dev.valnook.feature.bills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.valnook.designsystem.*
import dev.valnook.domain.repository.BillAccountChoice

/**
 * Picks the sub-account of a bill. Pinned sub-accounts come first; "all accounts" lists every one by account, each with
 * a star to pin it. A transfer's side can also be an outside account.
 */
@Composable fun BillAccountPicker(accounts: List<BillAccountChoice>, selected: Long?, allowExternal: Boolean,
    externalSelected: Boolean, onPick: (Long?) -> Unit, onFavorites: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    val favorites = accounts.filter { it.favorite }.sortedBy { it.favoriteOrder }
    var showAll by rememberSaveable { mutableStateOf(favorites.isEmpty()) }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    AnimatedGlassDialog(true, onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        GlassSurface(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(12.dp).testTag("bill-account-picker"),
            shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showAll && favorites.isNotEmpty() && !arranging) IconButton({ showAll = false; query = "" }) {
                        FaIcon(FaIcons.chevronLeft, stringResource(R.string.bill_favorites), Modifier.size(16.dp))
                    }
                    Text(stringResource(if (arranging) R.string.bill_arrange_favorites else if (showAll) R.string.bill_all_accounts
                        else R.string.bill_choose_account), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (!showAll && favorites.size > 1) TextButton({ arranging = !arranging }, Modifier.testTag("bill-arrange-favorites")) {
                        Text(stringResource(if (arranging) R.string.bill_done else R.string.bill_arrange))
                    }
                    IconButton(onDismiss) { FaIcon(FaIcons.xmark, stringResource(R.string.bill_close), Modifier.size(16.dp)) }
                }
                when {
                    arranging -> ReorderList(favorites.map { ReorderItem(it.cashAccountId.toString(), it.label(), it.currency.code) },
                        { keys -> onFavorites(keys.map(String::toLong)) }, stringResource(R.string.bill_arrange),
                        stringResource(R.string.bill_move_up), stringResource(R.string.bill_move_down), Modifier.weight(1f))
                    showAll -> {
                        GlassTextField(query, { query = it }, stringResource(R.string.bill_search_accounts),
                            Modifier.fillMaxWidth().testTag("bill-account-search"))
                        if (favorites.isEmpty()) HintMessage(stringResource(R.string.bill_favorites_hint))
                        val shown = accounts.filter { query.isBlank() || it.label().contains(query.trim(), ignoreCase = true) }
                            .sortedWith(compareBy({ it.accountOrder }, { it.accountId }, { !it.shownOnAccountsPage }, { it.cashOrder }))
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (allowExternal && query.isBlank()) item { ExternalRow(externalSelected) { onPick(null) } }
                            var lastAccount: Long? = null
                            shown.forEach { choice ->
                                if (choice.accountId != lastAccount) {
                                    lastAccount = choice.accountId
                                    item(key = "header-${choice.accountId}") {
                                        Text(choice.accountName, Modifier.padding(start = 4.dp, top = Space.md, bottom = 2.dp),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                item(key = choice.cashAccountId) {
                                    AccountRow(choice, choice.cashAccountId == selected && !externalSelected, { onPick(choice.cashAccountId) }) {
                                        val pinned = choice.favorite
                                        IconButton({
                                            onFavorites(if (pinned) favorites.map { it.cashAccountId } - choice.cashAccountId
                                                else favorites.map { it.cashAccountId } + choice.cashAccountId)
                                        }, Modifier.semantics { stateDescription = if (pinned) "★" else "☆" }
                                            .testTag("bill-favorite-${choice.cashAccountId}")) {
                                            Text(if (pinned) "★" else "☆", style = MaterialTheme.typography.titleLarge,
                                                color = if (pinned) billColor("amber") else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (allowExternal) item { ExternalRow(externalSelected) { onPick(null) } }
                        items(favorites, key = { it.cashAccountId }) { choice ->
                            AccountRow(choice, choice.cashAccountId == selected && !externalSelected, { onPick(choice.cashAccountId) }) {}
                        }
                        item {
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { showAll = true }
                                .padding(horizontal = 8.dp, vertical = 14.dp).testTag("bill-all-accounts"),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.bill_all_accounts), Modifier.weight(1f))
                                FaIcon(FaIcons.chevronRight, null, Modifier.size(10.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ExternalRow(selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick)
        .semantics { this.selected = selected }.padding(horizontal = 8.dp, vertical = 10.dp).testTag("bill-external"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        CategoryIcon("globe", "grey", size = 36.dp, selected = selected)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.bill_external_account))
            Text(stringResource(R.string.bill_external_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
