package dev.valnook.feature.accounts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.valnook.domain.model.AccountGroup

/** One group and its items, both in display order. */
data class AccountGroupSection<T>(val group: AccountGroup, val items: List<T>)

/**
 * Splits [items] (already in display order) into [groups]. Items pointing at an unknown group are shown in
 * the default group, matching how the database orders them.
 */
fun <T> sectionsOf(groups: List<AccountGroup>, items: List<T>, groupOf: (T) -> Long): List<AccountGroupSection<T>> {
    val known = groups.map { it.id }.toSet()
    val byGroup = items.groupBy { groupOf(it).takeIf { id -> id in known } ?: AccountGroup.DEFAULT_ID }
    return groups.map { AccountGroupSection(it, byGroup[it.id].orEmpty()) }
}

/** Groups only become visible once the user has created one besides the default group. */
fun hasCustomGroups(groups: List<AccountGroup>): Boolean = groups.any { !it.isDefault }

@Composable
fun accountGroupName(group: AccountGroup): String =
    if (group.isDefault) stringResource(R.string.account_group_default) else group.name
