package dev.valnook.feature.deposits

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.Currency
import java.time.LocalDate

@Composable fun DepositForm(vm: DepositFormViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val cashAccounts by vm.cashAccounts.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(stringResource(if (state.failed) R.string.deposit_load_failed else R.string.deposit_loading))
        return }
    val title = stringResource(when (vm.mode) { DepositFormMode.CREATE -> R.string.deposit_open
        DepositFormMode.EDIT -> R.string.deposit_edit
        DepositFormMode.CLOSE -> R.string.deposit_settle })
    FormLayout(title, submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        CurrencyChoice(state.currency.code, { value -> vm.update { it.copy(currency = Currency.of(value)) } },
            submission.editable && vm.mode == DepositFormMode.CREATE, Currency.supported.map { it.code to it.name })
        if (vm.mode != DepositFormMode.CLOSE) {
            Field(stringResource(R.string.deposit_principal), state.principalInput, { value -> vm.update { it.copy(principalInput = value) } }, true, submission.editable)
            Field(stringResource(R.string.deposit_rate), state.rateInput, { value -> vm.update { it.copy(rateInput = value) } }, true, submission.editable)
            DateField(stringResource(R.string.deposit_start_date), state.startDate.toString(), { value -> vm.update { it.copy(startDate = LocalDate.parse(value)) } }, submission.editable)
            DateField(stringResource(R.string.deposit_end_date), state.endDate.toString(), { value -> vm.update { it.copy(endDate = LocalDate.parse(value)) } }, submission.editable)
        } else Text(stringResource(R.string.deposit_principal_value, state.principalInput, state.currency.code))
        Text(stringResource(R.string.deposit_interest_value, vm.preview() ?: "—", state.currency.code))
        CheckboxRow(stringResource(if (vm.mode == DepositFormMode.CLOSE) R.string.deposit_link_close else R.string.deposit_link_open),
            state.cashLinked, vm::setOpenCashLinked, enabled = submission.editable)
        if (state.cashLinked) {
            val candidates = cashAccounts.filter { it.currency == state.currency }
            if (candidates.isEmpty()) Text(stringResource(R.string.deposit_cash_missing), color = MaterialTheme.colorScheme.error)
            else ChoiceField(stringResource(R.string.deposit_cash_account), state.cashAccountId?.toString().orEmpty(), candidates.map {
                it.id.toString() to (it.name + " · " + it.currency.code + " · " +
                    dev.valnook.domain.money.DecimalRules.format_display(it.balance_minor, it.currency.fraction_digits))
            }, { vm.selectOpenCashAccount(it.toLong()) }, submission.editable)
        }
        if (vm.mode == DepositFormMode.EDIT && state.closeCashLinked != null)
            CheckboxRow(stringResource(R.string.deposit_link_close), state.closeCashLinked == true,
                vm::setCloseCashLinked, enabled = submission.editable)
        if (vm.mode == DepositFormMode.EDIT && state.closeCashLinked == true) {
            val candidates = cashAccounts.filter { it.currency == state.currency }
            if (candidates.isEmpty()) Text(stringResource(R.string.deposit_cash_missing), color = MaterialTheme.colorScheme.error)
            else ChoiceField(stringResource(R.string.deposit_settlement_cash_account), state.closeCashAccountId?.toString().orEmpty(), candidates.map {
                it.id.toString() to (it.name + " · " + it.currency.code + " · " +
                    dev.valnook.domain.money.DecimalRules.format_display(it.balance_minor, it.currency.fraction_digits))
            }, { vm.selectCloseCashAccount(it.toLong()) }, submission.editable)
        }
        Text(stringResource(R.string.deposit_link_hint))
        vm.cashImpactPreview()?.let { Text(stringResource(R.string.deposit_cash_change, it)) }
        ErrorMessage(submission.error?.name)
    }
}
