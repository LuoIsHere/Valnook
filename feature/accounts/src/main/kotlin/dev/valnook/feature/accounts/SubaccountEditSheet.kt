package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.Currency
import kotlinx.coroutines.launch

/**
 * Edits one subaccount of the draft. Typing goes straight into the draft, so closing the panel keeps it; the
 * toolbar's save still saves everything at once.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubaccountEditSheet(row: CashAccountRowDraft, state: AccountEditUiState, editable: Boolean,
    vm: AccountEditViewModel, onDelete: (Long) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun close() { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }
    val errors = state.fieldErrors
    val first = errors.keys.firstOrNull()
    fun error(field: String) = errors["${row.key}:$field"]?.name
    fun attention(field: String) = first == "${row.key}:$field"
    val stacked = LocalDensity.current.fontScale > 1.3f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background) {
        PopupBlurEffect()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("subaccount-sheet")
            .padding(start = Space.md, end = Space.md, bottom = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.nameInput.ifBlank { stringResource(R.string.account_new_subaccount) }, Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(::close, Modifier.testTag("subaccount-sheet-done")) { Text(stringResource(R.string.account_sheet_done)) }
            }
            GlassCard {
                Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val types = listOf(BalanceAccountType.SAVINGS, BalanceAccountType.CREDIT)
                    CapsuleChoiceRow(types, row.type, { vm.changeRow(row.key, type = it) },
                        Modifier.fillMaxWidth().testTag("subaccount-type"),
                        enabled = editable && row.cashAccountId == null) { type ->
                        Text(stringResource(if (type == BalanceAccountType.CREDIT) R.string.account_type_credit
                            else R.string.account_type_savings), style = MaterialTheme.typography.labelLarge)
                    }
                    FilledField(stringResource(R.string.account_balance_name), row.nameInput, { vm.changeRow(row.key, name = it) },
                        enabled = editable, error = error("name"), requestAttention = attention("name"))
                    FilledField(stringResource(R.string.account_note), row.noteInput, { vm.changeRow(row.key, note = it) },
                        enabled = editable, placeholder = stringResource(R.string.account_optional),
                        error = error("note"), requestAttention = attention("note"))
                    SideBySide(stacked, 1.3f, {
                        CurrencyChoice(row.currency.code, { vm.changeRow(row.key, currency = Currency.of(it)) },
                            editable && !row.currencyLocked, Currency.options(interfaceLanguage())) { open ->
                            FilledChoiceField(stringResource(dev.valnook.core.designsystem.R.string.currency), row.currency.code,
                                open, enabled = editable && !row.currencyLocked)
                        }
                    }, {
                        FilledField(stringResource(R.string.account_balance), row.balanceInput, { vm.changeRow(row.key, balance = it) },
                            numeric = true, signed = true, enabled = editable,
                            error = error("balance"), requestAttention = attention("balance"))
                    })
                    if (row.type == BalanceAccountType.CREDIT) {
                        val sourceOptions = listOf("" to stringResource(R.string.account_independent_limit)) + state.creditSources
                            .filter { it.id != row.cashAccountId && it.currency == row.currency }
                            .map { it.id.toString() to it.label }
                        FilledDropdownField(stringResource(R.string.account_limit_source), row.limitSourceAccountId?.toString().orEmpty(),
                            sourceOptions, { selected -> vm.changeRow(row.key,
                                limitSourceAccountId = selected.toLongOrNull(), clearLimitSource = selected.isEmpty()) }, enabled = editable)
                        if (row.limitSourceAccountId == null) FilledField(stringResource(R.string.account_credit_limit),
                            row.creditLimitInput, { vm.changeRow(row.key, creditLimit = it) }, numeric = true, enabled = editable,
                            error = error("limit"), requestAttention = attention("limit"))
                        SideBySide(stacked, 1.4f, {
                            FilledField(stringResource(R.string.account_statement_day), row.statementDayInput,
                                { vm.changeRow(row.key, statementDay = it) }, numeric = true, enabled = editable,
                                error = error("statement"), requestAttention = attention("statement"))
                        }, {
                            FilledDropdownField(stringResource(R.string.account_due_rule), row.dueRuleType,
                                listOf("AFTER_STATEMENT_DAYS" to stringResource(R.string.account_due_after),
                                    "FIXED_DAY_OF_MONTH" to stringResource(R.string.account_due_fixed)),
                                { vm.changeRow(row.key, dueRuleType = it) }, enabled = editable)
                        })
                        FilledField(if (row.dueRuleType == "FIXED_DAY_OF_MONTH") stringResource(R.string.account_due_day)
                            else stringResource(R.string.account_due_days_after), row.dueRuleValueInput,
                            { vm.changeRow(row.key, dueRuleValue = it) }, numeric = true, enabled = editable,
                            error = error("due"), requestAttention = attention("due"))
                    }
                    val switchPadding = PaddingValues(vertical = 4.dp)
                    FormSwitchRow(stringResource(R.string.account_show_cash), row.showOnAccountsPage,
                        { vm.changeRow(row.key, showOnAccountsPage = it) }, enabled = editable, contentPadding = switchPadding)
                    if (row.type == BalanceAccountType.SAVINGS) FormSwitchRow(stringResource(R.string.account_include_available),
                        row.includeInAvailableCash, { vm.changeRow(row.key, includeInAvailableCash = it) },
                        enabled = editable, contentPadding = switchPadding)
                }
            }
            val saved = row.cashAccountId
            if (saved != null) ActionButton({ onDelete(saved) }, Modifier.fillMaxWidth().testTag("account-delete-$saved"),
                enabled = editable, destructive = true) { Text(stringResource(R.string.account_delete_balance_account)) }
            else ActionButton({ vm.removeRow(row.key); onDismiss() }, Modifier.fillMaxWidth().testTag("subaccount-cancel"),
                enabled = editable) { Text(stringResource(R.string.account_cancel_balance_account)) }
        }
    }
}

/** Two inputs on one line, or one under the other when large text would squeeze them. */
@Composable
private fun SideBySide(stacked: Boolean, secondWeight: Float, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { first(); second() }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(secondWeight)) { second() }
    }
}
