package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.AccountGroup
import dev.valnook.domain.model.AccountGroupRules
import dev.valnook.feature.accounts.AccountGroupsViewModel.Companion.accountKey
import dev.valnook.feature.accounts.AccountGroupsViewModel.Companion.groupKey

/** Top bar actions of the group management page, hosted by the app's root toolbar. */
data class AccountGroupsToolbarState(val busy: Boolean, val canSortGroups: Boolean,
    val sortGroups: () -> Unit, val addGroup: () -> Unit)

@Composable
fun AccountGroupsActions(state: AccountGroupsToolbarState?) {
    if (state == null) return
    val sortLabel = stringResource(R.string.account_group_sort)
    val addLabel = stringResource(R.string.account_group_new)
    IconButton(state.sortGroups, Modifier.testTag("account-groups-sort").semantics { contentDescription = sortLabel },
        enabled = !state.busy && state.canSortGroups) { SortIcon() }
    IconButton(state.addGroup, Modifier.testTag("account-groups-add").semantics { contentDescription = addLabel },
        enabled = !state.busy) { Text("＋") }
}

private sealed interface GroupDialog {
    data object Create : GroupDialog
    data object SortGroups : GroupDialog
    data class Rename(val group: AccountGroup) : GroupDialog
    data class Delete(val section: GroupedAccounts) : GroupDialog
    data class AddAccounts(val group: AccountGroup) : GroupDialog
}

/** Opens in edit mode and saves each change immediately; leaving the page needs no confirmation. */
@Composable
fun AccountGroupsScreen(vm: AccountGroupsViewModel, onToolbarChange: (AccountGroupsToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<GroupDialog?>(null) }
    val toolbar = remember(state.busy, state.sections.size) {
        AccountGroupsToolbarState(state.busy, state.sections.size > 1,
            { dialog = GroupDialog.SortGroups }, { dialog = GroupDialog.Create })
    }
    SideEffect { onToolbarChange(toolbar) }
    DisposableEffect(Unit) { onDispose { onToolbarChange(null) } }

    Column(Modifier.fillMaxSize().padding(pageContentPadding()), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        if (!state.loaded) {
            if (state.loadFailed) TextButton(vm::reload) { Text(stringResource(R.string.account_edit_load_failed)) }
            else CircularProgressIndicator()
            return@Column
        }
        Text(stringResource(R.string.account_groups_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.saveFailed) Text(stringResource(R.string.account_group_save_failed), color = MaterialTheme.colorScheme.error)
        val sectionsByHeader = state.sections.associateBy { groupKey(it.group.id) }
        ReorderList(groupedRows(state.sections, stringResource(R.string.account_group_default)), vm::reorderAccounts,
            stringResource(R.string.account_order_drag), stringResource(R.string.account_order_up),
            stringResource(R.string.account_order_down), Modifier.fillMaxWidth().weight(1f), !state.busy,
            leadingContent = { item ->
                val icon = state.sections.firstNotNullOf { section -> section.items.firstOrNull { accountKey(it.id) == item.key } }.icon
                AccountAvatar(icon.symbol, icon.imageKey)
            },
            headerContent = { item ->
                val section = sectionsByHeader[item.key]
                if (section == null) EmptyGroupRow()
                else GroupHeader(section, !state.busy,
                    onAdd = { dialog = GroupDialog.AddAccounts(section.group) },
                    onRename = { dialog = GroupDialog.Rename(section.group) },
                    onDelete = { dialog = GroupDialog.Delete(section) })
            })
    }

    when (val current = dialog) {
        null -> Unit
        GroupDialog.Create -> GroupNameDialog(stringResource(R.string.account_group_new), "",
            stringResource(R.string.account_group_create), { dialog = null }) { vm.create(it); dialog = null }
        is GroupDialog.Rename -> GroupNameDialog(stringResource(R.string.account_group_rename), current.group.name,
            stringResource(R.string.account_save), { dialog = null }) { vm.rename(current.group, it); dialog = null }
        is GroupDialog.Delete -> DeleteGroupDialog(current.section, { dialog = null }) {
            vm.delete(current.section.group); dialog = null
        }
        is GroupDialog.AddAccounts -> AddAccountsDialog(current.group, state.sections, { dialog = null }) {
            vm.addAccounts(current.group, it); dialog = null
        }
        GroupDialog.SortGroups -> SortGroupsDialog(state.sections, !state.busy, vm::reorderGroups) { dialog = null }
    }
}

/** Every group gets a header row; an empty group also gets a placeholder row so it can be seen. */
private fun groupedRows(sections: List<GroupedAccounts>, defaultGroupName: String): List<ReorderItem> =
    sections.flatMap { section ->
        val id = section.group.id.toString()
        val name = if (section.group.isDefault) defaultGroupName else section.group.name
        listOf(ReorderItem(groupKey(section.group.id), name, section = id, header = true)) +
            if (section.items.isEmpty()) listOf(ReorderItem("empty-$id", "", section = id, header = true))
            else section.items.map { ReorderItem(accountKey(it.id), it.name, it.note, section = id) }
    }

@Composable
private fun GroupHeader(section: GroupedAccounts, enabled: Boolean,
    onAdd: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val group = section.group
    var menu by remember { mutableStateOf(false) }
    val addLabel = stringResource(R.string.account_group_add_accounts)
    val moreLabel = stringResource(R.string.account_group_more)
    Row(Modifier.fillMaxWidth().padding(top = Space.md).testTag("account-group-${group.id}"),
        verticalAlignment = Alignment.CenterVertically) {
        Text(accountGroupName(group), Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onAdd, Modifier.testTag("account-group-add-${group.id}").semantics { contentDescription = addLabel },
            enabled = enabled) { Text("＋") }
        // The default group cannot be renamed or deleted; keep its add button aligned with the others.
        if (group.isDefault) Spacer(Modifier.size(48.dp))
        else Box {
            IconButton({ menu = true }, Modifier.testTag("account-group-more-${group.id}").semantics { contentDescription = moreLabel },
                enabled = enabled) { MoreIcon() }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(stringResource(R.string.account_group_rename)) }, { menu = false; onRename() })
                DropdownMenuItem({ Text(stringResource(R.string.account_group_delete), color = MaterialTheme.colorScheme.error) },
                    { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun EmptyGroupRow() {
    Text(stringResource(R.string.account_group_empty), Modifier.padding(start = 12.dp, top = Space.xs, bottom = Space.sm),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GroupNameDialog(title: String, initial: String, confirmLabel: String,
    onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val valid = runCatching { AccountGroupRules.name(name) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            GlassTextField(name, { name = it }, stringResource(R.string.account_group_name),
                Modifier.fillMaxWidth().testTag("account-group-name"))
        },
        confirmButton = {
            TextButton({ onConfirm(name) }, enabled = valid, modifier = Modifier.testTag("account-group-name-confirm")) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.account_order_cancel)) } })
}

@Composable
private fun DeleteGroupDialog(section: GroupedAccounts, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.account_group_delete_title)) },
        text = { Text(pluralStringResource(R.plurals.account_group_delete_message, section.items.size,
            section.group.name, section.items.size)) },
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("account-group-delete-confirm")) {
                Text(stringResource(R.string.account_group_delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.account_order_cancel)) } })
}

/** Lists the accounts of every other group; choosing one moves it, since an account belongs to one group only. */
@Composable
private fun AddAccountsDialog(target: AccountGroup, sections: List<GroupedAccounts>,
    onDismiss: () -> Unit, onConfirm: (List<Long>) -> Unit) {
    val candidates = sections.filter { it.group.id != target.id }.flatMap { section -> section.items.map { it to section.group } }
    var selected by remember { mutableStateOf(emptyList<Long>()) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_group_add_title, accountGroupName(target))) },
        text = {
            if (candidates.isEmpty()) Text(stringResource(R.string.account_group_add_empty))
            else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                candidates.forEach { (account, group) ->
                    CheckboxRow(stringResource(R.string.account_group_account_origin, account.name, accountGroupName(group)),
                        account.id in selected, { checked -> selected = if (checked) selected + account.id else selected - account.id },
                        Modifier.testTag("account-group-candidate-${account.id}"))
                }
            }
        },
        confirmButton = {
            TextButton({ onConfirm(selected) }, enabled = selected.isNotEmpty(),
                modifier = Modifier.testTag("account-group-add-confirm")) { Text(stringResource(R.string.account_group_add_confirm)) }
        },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.account_order_cancel)) } })
}

/** Each drop is saved at once, like every other change on this page. */
@Composable
private fun SortGroupsDialog(sections: List<GroupedAccounts>, enabled: Boolean,
    onReorder: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.account_group_sort)) },
        text = {
            ReorderList(sections.map { ReorderItem(it.group.id.toString(), accountGroupName(it.group)) },
                { keys -> onReorder(keys.map(String::toLong)) },
                stringResource(R.string.account_order_drag), stringResource(R.string.account_order_up),
                stringResource(R.string.account_order_down), Modifier.fillMaxWidth().heightIn(max = 360.dp), enabled)
        },
        confirmButton = {
            TextButton(onDismiss, Modifier.testTag("account-groups-sort-done")) { Text(stringResource(R.string.account_group_done)) }
        })
}
