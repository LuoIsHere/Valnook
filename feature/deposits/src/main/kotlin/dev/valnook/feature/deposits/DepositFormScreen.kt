package dev.valnook.feature.deposits

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.Currency
import dev.valnook.domain.money.DecimalRules
import java.time.LocalDate
import dev.valnook.core.designsystem.R as CoreR

/** What the toolbar's save shows and does for the deposit form. */
data class DepositToolbarState(val busy: Boolean, val enabled: Boolean, val label: String, val save: () -> Unit)

@Composable fun DepositSaveAction(state: DepositToolbarState?) {
    IconButton({ state?.save?.invoke() }, enabled = state?.enabled == true,
        modifier = Modifier.testTag("deposit-form-save").semantics { contentDescription = state?.label.orEmpty() }) {
        if (state?.busy == true) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else FaIcon(FaIcons.check, modifier = Modifier.size(22.dp))
    }
}

@Composable fun depositFormTitle(mode: DepositFormMode): String = stringResource(when (mode) {
    DepositFormMode.CREATE -> R.string.deposit_open
    DepositFormMode.EDIT -> R.string.deposit_edit
    DepositFormMode.CLOSE -> R.string.deposit_settle
})

/**
 * Opening, editing and settling a deposit, in grouped cards like the account form; the toolbar saves. Settling
 * shows the deposit and asks only where its money goes.
 */
@Composable fun DepositForm(vm: DepositFormViewModel, onBack: () -> Unit, onToolbar: (DepositToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val cashAccounts by vm.cashAccounts.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = submission.phase == SubmissionPhase.WORKING
    val saveLabel = stringResource(CoreR.string.save)
    SideEffect { onToolbar(DepositToolbarState(busy, state.loaded && !busy && submission.phase != SubmissionPhase.SUCCEEDED,
        saveLabel) { focus.clearFocus(); keyboard?.hide(); vm.submit() }) }
    DisposableEffect(onToolbar) { onDispose { onToolbar(null) } }
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(stringResource(if (state.failed) R.string.deposit_load_failed else R.string.deposit_loading))
        return }
    val editable = submission.editable
    val stacked = LocalDensity.current.fontScale > 1.3f
    val candidates = cashAccounts.filter { it.currency == state.currency }
    FormLayout(depositFormTitle(vm.mode), busy, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit,
        showSaveButton = false) {
        if (vm.mode == DepositFormMode.CLOSE) SettledDeposit(state) else {
            GlassCard {
                Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val currencyLabel = stringResource(CoreR.string.currency)
                    val currencyEditable = editable && vm.mode == DepositFormMode.CREATE
                    SideBySide(stacked, 2f, {
                        CurrencyChoice(state.currency.code, { value -> vm.changeCurrency(Currency.of(value)) },
                            currencyEditable, Currency.options(interfaceLanguage())) { open ->
                            FilledChoiceField(currencyLabel, state.currency.code, open, enabled = currencyEditable)
                        }
                    }, {
                        FilledField(stringResource(R.string.deposit_principal), state.principalInput,
                            { value -> vm.update { it.copy(principalInput = value) } }, numeric = true, enabled = editable)
                    })
                    FilledField(stringResource(R.string.deposit_rate), state.rateInput,
                        { value -> vm.update { it.copy(rateInput = value) } }, numeric = true, enabled = editable)
                }
            }
            FormSection(stringResource(R.string.deposit_term_section)) {
                Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CapsuleChoiceRow(TERM_MONTHS, wholeMonths(state.startDate, state.endDate) ?: -1, vm::applyTerm,
                        Modifier.fillMaxWidth().testTag("deposit-term"), optionModifier = { Modifier.testTag("deposit-term-$it") },
                        enabled = editable) { months ->
                        Text(monthsLabel(months), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                            autoSize = TextAutoSize.StepBased(9.sp, MaterialTheme.typography.labelLarge.fontSize, .5.sp))
                    }
                    val startLabel = stringResource(R.string.deposit_start_date)
                    val endLabel = stringResource(R.string.deposit_end_date)
                    SideBySide(stacked, 1f, {
                        DateField(startLabel, state.startDate.toString(), { vm.changeStart(LocalDate.parse(it)) }, editable) { open ->
                            FilledChoiceField(startLabel, state.startDate.toString(), open, enabled = editable)
                        }
                    }, {
                        DateField(endLabel, state.endDate.toString(), { vm.changeEnd(LocalDate.parse(it)) }, editable) { open ->
                            FilledChoiceField(endLabel, state.endDate.toString(), open, enabled = editable)
                        }
                    })
                }
            }
        }
        FormSummary(vm.summary(), state.currency)
        FormSection(stringResource(R.string.deposit_cash_section)) {
            FormSwitchRow(stringResource(if (vm.mode == DepositFormMode.CLOSE) R.string.deposit_link_close else R.string.deposit_link_open),
                state.cashLinked, vm::setOpenCashLinked, enabled = editable)
            if (state.cashLinked) {
                FormDivider()
                CashAccountRow(stringResource(R.string.deposit_cash_account), state.cashAccountId, candidates,
                    vm::selectOpenCashAccount, editable)
            }
            if (vm.mode == DepositFormMode.EDIT && state.closeCashLinked != null) {
                FormDivider()
                FormSwitchRow(stringResource(R.string.deposit_link_close), state.closeCashLinked == true,
                    vm::setCloseCashLinked, enabled = editable)
                if (state.closeCashLinked == true) {
                    FormDivider()
                    CashAccountRow(stringResource(R.string.deposit_settlement_cash_account), state.closeCashAccountId,
                        candidates, vm::selectCloseCashAccount, editable)
                }
            }
        }
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            vm.cashImpactPreview()?.let { Text(stringResource(R.string.deposit_cash_change, it),
                Modifier.testTag("deposit-cash-change"), style = MaterialTheme.typography.bodyMedium) }
            Text(stringResource(R.string.deposit_link_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ErrorMessage(submission.error?.name)
    }
}

/** Settling changes nothing about the deposit itself, so it is shown, not edited. */
@Composable private fun SettledDeposit(state: DepositFormUiState) {
    GlassCard(Modifier.testTag("deposit-settle-summary")) {
        Column(Modifier.fillMaxWidth().padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.deposit_principal), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val minor = runCatching { DecimalRules.parse_minor(state.principalInput, state.currency, true) }.getOrNull()
            if (minor != null) DepositAmount(minor, state.currency)
            else Text(state.principalInput + " " + state.currency.code, style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.deposit_rate_value, state.rateInput) + " · " + state.startDate + " → " + state.endDate,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The term in days, the interest it earns and the total at maturity, as the inputs stand. */
@Composable private fun FormSummary(summary: DepositFormSummary, currency: Currency) {
    GlassCard(Modifier.testTag("deposit-form-summary")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryValue(stringResource(R.string.deposit_term_section),
                summary.days?.toInt()?.let { pluralStringResource(R.plurals.deposit_term_days, it, it) } ?: "—", Modifier.weight(1f))
            SummaryValue(stringResource(R.string.deposit_summary_interest),
                summary.interestMinor?.let { "+" + minorText(it, currency) } ?: "—", Modifier.weight(1f),
                if (summary.interestMinor != null) LocalGainLossPalette.current.gain else null)
            SummaryValue(stringResource(R.string.deposit_summary_total),
                summary.totalMinor?.let { minorText(it, currency) + " " + currency.code } ?: "—", Modifier.weight(1.4f))
        }
    }
}

@Composable private fun SummaryValue(label: String, value: String, modifier: Modifier,
    color: androidx.compose.ui.graphics.Color? = null) {
    val style = MaterialTheme.typography.bodyLarge
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = style.copy(fontFeatureSettings = "tnum"), color = color ?: MaterialTheme.colorScheme.onSurface,
            maxLines = 1, autoSize = TextAutoSize.StepBased(9.sp, style.fontSize, .5.sp))
    }
}

/** One line of the cash card: which same-currency cash account the money moves through. */
@Composable private fun CashAccountRow(label: String, selected: Long?, candidates: List<CashAccount>,
    onSelect: (Long) -> Unit, enabled: Boolean) {
    val colors = MaterialTheme.colorScheme
    if (candidates.isEmpty()) {
        Text(stringResource(R.string.deposit_cash_missing), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = colors.error)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val chosen = candidates.firstOrNull { it.id == selected }
    Box(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("input-$label")
            .clickable(enabled = enabled, role = Role.Button) { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            Text(chosen?.let { cashLabel(it) } ?: "—", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End, maxLines = 2,
                color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
            FaIcon(FaIcons.chevronDown, null, Modifier.size(14.dp), colors.onSurfaceVariant)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            candidates.forEach { account -> DropdownMenuItem(text = { Text(cashLabel(account)) },
                onClick = { onSelect(account.id); expanded = false }) }
        }
    }
}

private fun cashLabel(account: CashAccount) = account.name.ifBlank { account.currency.code } + " · " +
    minorText(account.balance_minor, account.currency) + " " + account.currency.code

/** Two inputs on one line, or one under the other when large text would squeeze them. */
@Composable private fun SideBySide(stacked: Boolean, secondWeight: Float, first: @Composable () -> Unit, second: @Composable () -> Unit) {
    if (stacked) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { first(); second() }
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { first() }
        Box(Modifier.weight(secondWeight)) { second() }
    }
}
