package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.valnook.domain.money.DecimalRules

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

@Composable fun AccountsScreen(vm: AccountsViewModel, onOpen: (Long) -> Unit,
    onOpenCash: (Long, Long) -> Unit = { _, _ -> }, onOpenDeposits: (Long) -> Unit = {},
    onOpenInvestments: (Long) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountsState.Loading -> CircularProgressIndicator()
        AccountsState.Failed -> Text(stringResource(R.string.accounts_load_failed))
        is AccountsState.Ready -> AccountsContent(current.overview, onOpen, current.snapshot,
            onOpenCash, onOpenDeposits, onOpenInvestments)
    }
}
@Composable fun AccountsContent(overview: AssetOverview, onOpen: (Long) -> Unit,
    snapshot: AssetSnapshot? = null, onOpenCash: (Long, Long) -> Unit = { _, _ -> },
    onOpenDeposits: (Long) -> Unit = {}, onOpenInvestments: (Long) -> Unit = {}) {
    var expandedId by rememberSaveable { mutableStateOf<Long?>(null) }
    LazyColumn(Modifier.fillMaxSize().testTag("accounts-list"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = Space.md), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.accounts_total_assets), style = MaterialTheme.typography.titleMedium)
                Text(totalText(overview.total), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings="tnum"),
                    color = amountColor(overview.total))
                SummaryLine(stringResource(R.string.accounts_cash), overview.cash)
                SummaryLine(stringResource(R.string.accounts_deposits), overview.depositValue)
                SummaryLine(stringResource(R.string.accounts_investments), overview.investmentValue)
                if (!overview.total.complete) Text(stringResource(R.string.accounts_summary_incomplete, missingText(overview.total)), color = MaterialTheme.colorScheme.error)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        if (overview.accounts.isEmpty()) item { EmptyState(stringResource(R.string.accounts_empty)) }
        items(overview.accounts, key = { it.account.id }) { row ->
            val isExpanded = expandedId == row.account.id
            val cashRows = snapshot?.cash?.filter { it.account_id == row.account.id }.orEmpty()
            val depositCount = snapshot?.deposits?.count { it.account_id == row.account.id && !it.closed } ?: 0
            val investmentCount = snapshot?.positions?.count { it.account_id == row.account.id && it.holding_quantity_e8 > 0 } ?: 0
            Column(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.fillMaxWidth().testTag("account-toggle-${row.account.id}")
                    .clickable { expandedId = if (isExpanded) null else row.account.id }
                    .padding(vertical = 4.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val name: @Composable () -> Unit = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.account.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (isExpanded) "▴" else "▾", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val amount: @Composable () -> Unit = {
                            Text(totalText(row.total), Modifier.fillMaxWidth().testTag("account-total-${row.account.id}")
                                .clickable { onOpen(row.account.id) },
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings="tnum"),
                                color = amountColor(row.total), textAlign = TextAlign.End)
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.account.note, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodySmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.accounts_available_cash, totalText(row.cash)), Modifier.weight(1.3f).alignByBaseline(),
                            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.End)
                    }
                }
                if (!row.total.complete) Text(stringResource(R.string.accounts_partial_summary, missingText(row.total)), color = MaterialTheme.colorScheme.error)
                AnimatedVisibility(isExpanded, enter = expandVertically(tween(140)) + fadeIn(tween(90)),
                    exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
                    Column(Modifier.fillMaxWidth().padding(start = Space.sm), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        cashRows.forEach { cash ->
                            AccountNativeRow(cash.name.ifBlank { cash.currency.code }, cash.note,
                                DecimalRules.format_display(cash.balance_minor, cash.currency.fraction_digits) + " " + cash.currency.code,
                                cash.balance_minor < 0, "account-cash-${cash.id}") { onOpenCash(row.account.id, cash.id) }
                        }
                        if (cashRows.isEmpty()) Text(stringResource(R.string.accounts_no_cash),
                            Modifier.padding(vertical = Space.sm), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        AccountSummaryRow(stringResource(R.string.accounts_deposit_count, depositCount), row.depositValue) {
                            onOpenDeposits(row.account.id)
                        }
                        AccountSummaryRow(stringResource(R.string.accounts_investment_count, investmentCount), row.investmentValue) {
                            onOpenInvestments(row.account.id)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable private fun amountColor(total: ConvertedTotal) =
    if (total.amount.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

@Composable private fun SummaryLine(label: String, total: ConvertedTotal) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(totalText(total), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings="tnum"),
            color = amountColor(total), textAlign = TextAlign.End)
    }
}

@Composable private fun AccountNativeRow(title: String, note: String, amount: String, negative: Boolean, tag: String,
    onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(tag).clickable(onClick = onClick).padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(amount, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"),
            color = if (negative) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun AccountSummaryRow(label: String, total: ConvertedTotal, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(totalText(total), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"),
            color = amountColor(total))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        { vm.changeRow(row.key, balance = it) }, true, submission.editable, signed = true) }
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
