package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.command.SubmissionPhase
import java.math.RoundingMode

@Composable private fun totalText(total: ConvertedTotal): String = total.currency?.let {
    total.amount.setScale(it.fraction_digits, RoundingMode.HALF_UP).toPlainString() + " " + it.code
} ?: stringResource(R.string.accounts_set_base_currency)

@Composable private fun missingText(total: ConvertedTotal): String = total.missing.mapNotNull {
    when (it.kind) {
        MissingKind.BASE_CURRENCY -> stringResource(R.string.accounts_missing_base)
        MissingKind.EXCHANGE_RATE -> null
        MissingKind.CURRENT_COST -> stringResource(R.string.accounts_missing_current_cost)
        MissingKind.HISTORICAL_COST -> stringResource(R.string.accounts_missing_historical_cost)
        MissingKind.INVALID_HISTORY -> stringResource(R.string.accounts_invalid_history)
    }
}.distinct().joinToString(" · ")

@Composable fun AccountsScreen(vm: AccountsViewModel, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountsState.Loading -> CircularProgressIndicator()
        AccountsState.Failed -> Text(stringResource(R.string.accounts_load_failed))
        is AccountsState.Ready -> AccountsContent(current.overview, onOpen)
    }
}
@Composable fun AccountsContent(overview: AssetOverview, onOpen: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("accounts-list"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(stringResource(R.string.accounts_total_assets), style = MaterialTheme.typography.titleMedium)
                Text(totalText(overview.total), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.accounts_available_cash, totalText(overview.cash)), style = MaterialTheme.typography.bodyMedium)
                if (!overview.total.complete) Text(stringResource(R.string.accounts_summary_incomplete, missingText(overview.total)), color = MaterialTheme.colorScheme.error)
            }
        }
        if (overview.accounts.isEmpty()) item { EmptyState(stringResource(R.string.accounts_empty)) }
        items(overview.accounts, key = { it.account.id }) { row ->
            OutlinedCard(onClick = { onOpen(row.account.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val name: @Composable () -> Unit = {
                            Text(row.account.name, style = MaterialTheme.typography.titleLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val amount: @Composable () -> Unit = {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(totalText(row.total), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                name()
                                amount()
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { name() }
                            Box(Modifier.weight(1.3f)) { amount() }
                        }
                    }
                    if (!row.total.complete) Text(stringResource(R.string.accounts_partial_summary, missingText(row.total)), color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.account.note, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.accounts_available_cash, totalText(row.cash)), Modifier.weight(1.3f).alignByBaseline(),
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                    }
                }
            }
        }
    }
}
@Composable fun AccountEditScreen(vm: AccountEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) {
        if (state.loadError) TextButton(onClick = vm::reload) { Text(stringResource(R.string.account_edit_load_failed)) } else CircularProgressIndicator()
        return
    }
    FormLayout(stringResource(R.string.account_edit_title), submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit,
        if (submission.phase == SubmissionPhase.UNKNOWN) stringResource(R.string.account_review_retry) else stringResource(R.string.account_save)) {
        Field(stringResource(R.string.account_name), state.name, vm::changeName, enabled = submission.editable)
        Field(stringResource(R.string.account_note), state.note, vm::changeNote, enabled = submission.editable)
        Text(stringResource(R.string.account_cash_accounts), style = MaterialTheme.typography.titleMedium)
        state.rows.forEach { row ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Field(stringResource(R.string.account_cash_name), row.nameInput, { vm.changeRow(row.key, name = it) }, enabled = submission.editable)
                Field(stringResource(R.string.account_note), row.noteInput, { vm.changeRow(row.key, note = it) }, enabled = submission.editable)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        CurrencyChoice(row.currency.code, { vm.changeRow(row.key, currency = Currency.of(it)) },
                            submission.editable && !row.currencyLocked, Currency.supported.map { it.code to it.name })
                    }
                    Box(Modifier.weight(1.3f)) { Field(stringResource(R.string.account_balance), row.balanceInput,
                        { vm.changeRow(row.key, balance = it) }, true, submission.editable) }
                }
                if (!row.currencyLocked) TextButton(onClick = { vm.removeRow(row.key) }, enabled = submission.editable) {
                    Text(stringResource(R.string.account_cancel_cash))
                }
                }
            }
        }
        ActionButton(vm::addRow, enabled = submission.editable) { Text(stringResource(R.string.account_add_cash)) }
        ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.account_unknown_result))
    }
}
