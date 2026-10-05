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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditBillingFocus
import dev.valnook.domain.calculation.CreditLimitCalculator
import java.time.LocalDate

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
        AccountsState.Loading -> PageLoading()
        AccountsState.Failed -> PageFailure(stringResource(R.string.accounts_load_failed), vm::reload)
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
            GlassCard(Modifier.padding(bottom = Space.md).testTag("accounts-summary"), prominent = true) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.accounts_total_assets), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(totalText(overview.total), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings="tnum"),
                    color = amountColor(overview.total))
                val metrics = buildList {
                    add(stringResource(R.string.accounts_cash) to overview.cash)
                    add(stringResource(R.string.accounts_investments) to overview.investmentValue)
                    add(stringResource(R.string.accounts_deposits) to overview.depositValue)
                    if (overview.creditBalance.amount.signum() != 0 ||
                        snapshot?.cash?.any { it.type == BalanceAccountType.CREDIT } == true)
                        add(stringResource(R.string.accounts_credit_balance) to overview.creditBalance)
                }
                MetricGrid(metrics.size) { index ->
                    val (label, total) = metrics[index]
                    SummaryMetric(label, totalText(total))
                }
                if (!overview.total.complete) HintMessage(stringResource(R.string.accounts_summary_incomplete, missingText(overview.total)))
            }
            }
        }
        if (overview.accounts.isEmpty()) item { EmptyState(stringResource(R.string.accounts_empty)) }
        items(overview.accounts, key = { it.account.id }) { row ->
            val isExpanded = expandedId == row.account.id
            val cashRows = snapshot?.cash?.filter { it.account_id == row.account.id }.orEmpty()
            val depositCount = snapshot?.deposits?.count { it.account_id == row.account.id && !it.closed } ?: 0
            val investmentCount = snapshot?.positions?.count { it.account_id == row.account.id && it.holding_quantity_e8 > 0 } ?: 0
            Column(Modifier.fillMaxWidth().padding(vertical = Space.sm), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.fillMaxWidth().testTag("account-header-${row.account.id}").padding(vertical = 4.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val name: @Composable () -> Unit = {
                            val expansion = stringResource(if (isExpanded) dev.valnook.core.designsystem.R.string.state_expanded
                                else dev.valnook.core.designsystem.R.string.state_collapsed)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .testTag("account-toggle-${row.account.id}")
                                .clickable(role = Role.Button) { expandedId = if (isExpanded) null else row.account.id }
                                .semantics { stateDescription = expansion },
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(row.account.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (isExpanded) "▴" else "▾", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val amount: @Composable () -> Unit = {
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .testTag("account-total-${row.account.id}").clickable(role = Role.Button) { onOpen(row.account.id) },
                                contentAlignment = Alignment.CenterEnd) {
                            Text(totalText(row.total),
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings="tnum"),
                                color = amountColor(row.total), textAlign = TextAlign.End)
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(row.account.note, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.accounts_available_cash, totalText(row.cash)), Modifier.weight(1.3f),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!row.total.complete) HintMessage(stringResource(R.string.accounts_partial_summary, missingText(row.total)))
                AnimatedVisibility(isExpanded, enter = expandVertically(tween(140)) + fadeIn(tween(90)),
                    exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
                    Column(Modifier.fillMaxWidth().padding(start = Space.sm), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        cashRows.forEach { cash ->
                            BalanceAccountNativeRow(cash, snapshot?.cash.orEmpty(), "account-cash-${cash.id}") {
                                onOpenCash(row.account.id, cash.id)
                            }
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

@Composable private fun BalanceAccountNativeRow(account: CashAccount, allAccounts: List<CashAccount>, tag: String,
    onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .testTag(tag).clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Column(Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(account.name.ifBlank { account.currency.code }, style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (account.type == BalanceAccountType.CREDIT) stringResource(R.string.account_type_credit)
                        else stringResource(R.string.account_type_savings), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (account.note.isNotBlank()) Text(account.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val amount = DecimalRules.format_display(account.balance_minor, account.currency.fraction_digits) + " " + account.currency.code
            Text(amount, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"),
                color = if (account.type == BalanceAccountType.CREDIT) MaterialTheme.colorScheme.onSurface
                    else if (account.balance_minor < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (account.type == BalanceAccountType.CREDIT) {
            val summary = remember(account.id, account.revision, allAccounts) {
                runCatching { CreditLimitCalculator.calculate(account.id, allAccounts) }.getOrNull()
            }
            val billing = account.creditProfile?.let { profile ->
                remember(profile, LocalDate.now()) {
                    runCatching { CreditBillingCalendar.calculate(LocalDate.now(), profile.statementDay, profile.dueRule) }.getOrNull()
                }
            }
            if (summary != null) {
                val scale = account.currency.fraction_digits
                LinearProgressIndicator(
                    progress = { if (summary.totalLimitMinor.signum() == 0) 0f else
                        summary.usedLimitMinor.divide(summary.totalLimitMinor, 4, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth())
                val used = DecimalRules.format_display(summary.usedLimitMinor.longValueExact(), scale)
                val limit = DecimalRules.format_display(summary.totalLimitMinor.longValueExact(), scale)
                Text(stringResource(R.string.account_credit_usage, used, limit, account.currency.code),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (summary.overLimitMinor.signum() > 0) Text(stringResource(R.string.account_credit_over_limit,
                    DecimalRules.format_display(summary.overLimitMinor.longValueExact(), scale), account.currency.code),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (billing != null) Text(when {
                billing.daysRemaining == 0L && billing.focus == CreditBillingFocus.STATEMENT -> stringResource(R.string.account_statement_today)
                billing.daysRemaining == 0L -> stringResource(R.string.account_due_today)
                billing.focus == CreditBillingFocus.DUE -> stringResource(R.string.account_due_in_days, billing.daysRemaining)
                else -> stringResource(R.string.account_statement_in_days, billing.daysRemaining)
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun AccountSummaryRow(label: String, total: ConvertedTotal, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
        .clickable(role = Role.Button, onClick = onClick).padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(totalText(total), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings="tnum"),
            color = amountColor(total))
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
@Composable fun AccountEditScreen(vm: AccountEditViewModel, onBack: () -> Unit,
    onSortActionChanged: ((() -> Unit)?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    var pendingDeleteKey by rememberSaveable { mutableStateOf<String?>(null) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    var sortKeys by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val latestSortActionChanged by rememberUpdatedState(onSortActionChanged)
    val latestRows by rememberUpdatedState(state.rows)
    DisposableEffect(state.loaded, state.rows.size, submission.editable) {
        latestSortActionChanged(if (state.loaded && state.rows.size > 1 && submission.editable) ({
            sortKeys = latestRows.map { it.key }
            sorting = true
        }) else null)
        onDispose { latestSortActionChanged(null) }
    }
    if (sorting) SubaccountOrderSheet(state.rows, sortKeys, { sortKeys = it },
        { vm.reorderRows(sortKeys); sorting = false }, { sorting = false })
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) {
        if (state.loadError) TextButton(onClick = vm::reload) { Text(stringResource(R.string.account_edit_load_failed)) } else CircularProgressIndicator()
        return
    }
    FormLayout(stringResource(R.string.account_edit_title), submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit,
        if (submission.phase == SubmissionPhase.UNKNOWN) stringResource(R.string.account_review_retry) else stringResource(R.string.account_save)) {
        GlassCard {
            Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Field(stringResource(R.string.account_name), state.name, vm::changeName, enabled = submission.editable)
                Field(stringResource(R.string.account_note), state.note, vm::changeNote, enabled = submission.editable)
            }
        }
        Text(stringResource(R.string.account_balance_accounts), style = MaterialTheme.typography.titleMedium)
        state.rows.forEach { row ->
            key(row.key) {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                ChoiceField(stringResource(R.string.account_type), row.type.name, listOf(
                    BalanceAccountType.SAVINGS.name to stringResource(R.string.account_type_savings),
                    BalanceAccountType.CREDIT.name to stringResource(R.string.account_type_credit)),
                    { vm.changeRow(row.key, type = BalanceAccountType.valueOf(it)) },
                    submission.editable && row.cashAccountId == null)
                Field(stringResource(R.string.account_balance_name), row.nameInput, { vm.changeRow(row.key, name = it) }, enabled = submission.editable)
                Field(stringResource(R.string.account_note), row.noteInput, { vm.changeRow(row.key, note = it) }, enabled = submission.editable)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        CurrencyChoice(row.currency.code, { vm.changeRow(row.key, currency = Currency.of(it)) },
                            submission.editable && !row.currencyLocked, Currency.supported.map { it.code to it.name })
                    }
                    Box(Modifier.weight(1.3f)) { Field(stringResource(R.string.account_balance), row.balanceInput,
                        { vm.changeRow(row.key, balance = it) }, true, submission.editable, signed = true) }
                }
                if (row.type == BalanceAccountType.CREDIT) {
                    val sourceOptions = listOf("" to stringResource(R.string.account_independent_limit)) + state.creditSources
                        .filter { it.id != row.cashAccountId && it.currency == row.currency }
                        .map { it.id.toString() to it.label }
                    ChoiceField(stringResource(R.string.account_limit_source), row.limitSourceAccountId?.toString().orEmpty(),
                        sourceOptions, { selected -> vm.changeRow(row.key,
                            limitSourceAccountId = selected.toLongOrNull(), clearLimitSource = selected.isEmpty()) }, submission.editable)
                    if (row.limitSourceAccountId == null) Field(stringResource(R.string.account_credit_limit), row.creditLimitInput,
                        { vm.changeRow(row.key, creditLimit = it) }, numeric = true, enabled = submission.editable)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) { Field(stringResource(R.string.account_statement_day), row.statementDayInput,
                            { vm.changeRow(row.key, statementDay = it) }, numeric = true, enabled = submission.editable) }
                        Box(Modifier.weight(1.4f)) { ChoiceField(stringResource(R.string.account_due_rule), row.dueRuleType,
                            listOf("AFTER_STATEMENT_DAYS" to stringResource(R.string.account_due_after),
                                "FIXED_DAY_OF_MONTH" to stringResource(R.string.account_due_fixed)),
                            { vm.changeRow(row.key, dueRuleType = it) }, submission.editable) }
                    }
                    Field(if (row.dueRuleType == "FIXED_DAY_OF_MONTH") stringResource(R.string.account_due_day)
                        else stringResource(R.string.account_due_days_after), row.dueRuleValueInput,
                        { vm.changeRow(row.key, dueRuleValue = it) }, numeric = true, enabled = submission.editable)
                }
                if (!row.currencyLocked) TextButton(onClick = { vm.removeRow(row.key) }, enabled = submission.editable) {
                    Text(stringResource(R.string.account_cancel_balance_account))
                }
                if (row.cashAccountId != null) TextButton(
                    onClick = { pendingDeleteKey = row.key },
                    enabled = submission.editable,
                    modifier = Modifier.testTag("account-delete-${row.cashAccountId}")) {
                    Text(stringResource(R.string.account_delete_balance_account), color = MaterialTheme.colorScheme.error)
                }
                }
            }
            }
        }
        ActionButton(vm::addRow, enabled = submission.editable) { Text(stringResource(R.string.account_add_balance_account)) }
        ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.account_unknown_result))
    }
    if (pendingDeleteKey != null) AlertDialog(
        onDismissRequest = { pendingDeleteKey = null },
        title = { PopupBlurEffect(); Text(stringResource(R.string.account_delete_title)) },
        text = { Text(stringResource(R.string.account_delete_message)) },
        dismissButton = { TextButton(onClick = { pendingDeleteKey = null }) {
            Text(stringResource(dev.valnook.core.designsystem.R.string.cancel))
        } },
        confirmButton = { TextButton(onClick = {
            val key = pendingDeleteKey
            pendingDeleteKey = null
            if (key != null) vm.deleteRow(key)
        }, modifier = Modifier.testTag("account-delete-confirm")) {
            Text(stringResource(R.string.account_delete_confirm), color = MaterialTheme.colorScheme.error)
        } }
    )
}
