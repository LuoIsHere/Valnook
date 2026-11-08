package dev.valnook.feature.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.money.DecimalRules

/** The draft row a field error belongs to, or null for the account's own name and note. */
internal fun errorRowKey(field: String): String? = field.substringBefore(':', "").ifEmpty { null }

@Composable fun AccountEditScreen(vm: AccountEditViewModel, onBack: () -> Unit,
    onSortActionChanged: ((() -> Unit)?) -> Unit = {}, onPickImage: () -> Unit = {},
    onSaveActionChanged: (AccountEditToolbarState?) -> Unit = {}, onDelete: (Long) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    var sorting by rememberSaveable { mutableStateOf(false) }
    var sortKeys by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var editingKey by rememberSaveable { mutableStateOf<String?>(null) }
    val latestSortActionChanged by rememberUpdatedState(onSortActionChanged)
    val latestRows by rememberUpdatedState(state.rows)
    val latestSaveChanged by rememberUpdatedState(onSaveActionChanged)
    // Keyed by the view model too: a new one at this place must hand its own submit to the toolbar.
    DisposableEffect(vm, state.loaded, submission.phase) {
        latestSaveChanged(if (state.loaded) AccountEditToolbarState(submission.phase == SubmissionPhase.WORKING,
            submission.phase != SubmissionPhase.SUCCEEDED && submission.phase != SubmissionPhase.WORKING, vm::submit) else null)
        onDispose { latestSaveChanged(null) }
    }
    DisposableEffect(vm, state.loaded, state.rows.size, submission.editable) {
        latestSortActionChanged(if (state.loaded && state.rows.size > 1 && submission.editable) ({
            sortKeys = latestRows.map { it.key }
            sorting = true
        }) else null)
        onDispose { latestSortActionChanged(null) }
    }
    // A save that stops at a subaccount's field opens that subaccount, so the field is in sight.
    LaunchedEffect(submission) {
        if (submission.phase == SubmissionPhase.INVALID) {
            val current = vm.state.value
            current.fieldErrors.keys.firstOrNull()?.let(::errorRowKey)
                ?.takeIf { key -> current.rows.any { it.key == key } }?.let { editingKey = it }
        }
    }
    if (sorting) SubaccountOrderSheet(state.rows, sortKeys, { sortKeys = it },
        { vm.reorderRows(sortKeys); sorting = false }, { sorting = false })
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) {
        if (state.loadError) TextButton(onClick = vm::reload) { Text(stringResource(R.string.account_edit_load_failed)) } else CircularProgressIndicator()
        return
    }
    val editable = submission.editable
    val firstError = state.fieldErrors.keys.firstOrNull()
    FormLayout(stringResource(R.string.account_edit_title), submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit,
        if (submission.phase == SubmissionPhase.UNKNOWN) stringResource(R.string.account_review_retry) else stringResource(R.string.account_save), showSaveButton = false) {
        AccountIconEditor(state, editable, vm::changeSymbol, onPickImage)
        FormSection(null) {
            FormTextRow(stringResource(R.string.account_name), state.name, vm::changeName, enabled = editable,
                error = state.fieldErrors["name"]?.name, requestAttention = firstError == "name")
            FormDivider()
            FormTextRow(stringResource(R.string.account_note), state.note, vm::changeNote, enabled = editable,
                placeholder = stringResource(R.string.account_optional),
                error = state.fieldErrors["note"]?.name, requestAttention = firstError == "note")
        }
        FormSection(stringResource(R.string.account_detail_settings)) {
            FormSwitchRow(stringResource(R.string.account_show_deposits), state.showDepositSummary,
                { vm.changeVisibility(deposits = it) }, enabled = editable)
            FormDivider()
            FormSwitchRow(stringResource(R.string.account_show_investments), state.showInvestmentSummary,
                { vm.changeVisibility(investments = it) }, enabled = editable)
        }
        FormSection(stringResource(R.string.account_subaccount_count, state.rows.size)) {
            state.rows.forEach { row -> key(row.key) {
                SubaccountSummaryRow(row, state.fieldErrors.keys.count { errorRowKey(it) == row.key }) { editingKey = row.key }
                FormDivider()
            } }
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account-add-row")
                .clickable(enabled = editable, role = Role.Button) {
                    val before = vm.state.value.rows.map { it.key }.toSet()
                    vm.addRow()
                    vm.state.value.rows.firstOrNull { it.key !in before }?.let { editingKey = it.key }
                }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.account_add_balance_account), style = MaterialTheme.typography.bodyLarge,
                    color = if (editable) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .38f))
            }
        }
        ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.account_unknown_result))
    }
    val editing = state.rows.firstOrNull { it.key == editingKey }
    LaunchedEffect(editingKey, editing == null) { if (editingKey != null && editing == null) editingKey = null }
    if (editing != null) SubaccountEditSheet(editing, state, editable, vm,
        onDelete = { id -> editingKey = null; onDelete(id) }, onDismiss = { editingKey = null })
}

@Composable
private fun SubaccountSummaryRow(row: CashAccountRowDraft, issues: Int, onOpen: () -> Unit) {
    // A balance still being typed shows as typed.
    val minor = try { DecimalRules.parse_signed_minor(row.balanceInput, row.currency) } catch (_: Exception) { null }
    val amount = minor?.let { DecimalRules.format_display(it, row.currency.fraction_digits) } ?: row.balanceInput.ifBlank { "—" }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).testTag("subaccount-row-${row.key}")
        .clickable(role = Role.Button, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                Text(row.nameInput.ifBlank { stringResource(R.string.account_new_subaccount) },
                    style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.type == BalanceAccountType.CREDIT && minor != null) CreditBalanceTag(minor)
                if (!row.showOnAccountsPage) StatusTag(stringResource(R.string.account_hidden), TagTone.NEUTRAL)
                if (issues > 0) StatusTag(pluralStringResource(R.plurals.account_issue_count, issues, issues), TagTone.NEGATIVE)
            }
            Text("$amount ${row.currency.code}", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RowChevron()
    }
}
