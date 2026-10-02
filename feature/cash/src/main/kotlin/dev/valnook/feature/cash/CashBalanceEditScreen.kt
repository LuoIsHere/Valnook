package dev.valnook.feature.cash

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.ErrorMessage
import dev.valnook.designsystem.Field
import dev.valnook.designsystem.FormLayout
import dev.valnook.domain.command.SubmissionPhase

@Composable
fun CashBalanceEditScreen(vm: CashBalanceEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) {
        if (vm.consumeSuccess()) onBack()
    }
    if (!state.loaded) {
        if (state.failed) Text(stringResource(R.string.cash_account_load_failed)) else CircularProgressIndicator()
        return
    }
    val account = requireNotNull(state.account)
    FormLayout(stringResource(R.string.cash_edit_balance), submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit,
        if (submission.phase == SubmissionPhase.UNKNOWN) stringResource(R.string.cash_review_retry) else stringResource(R.string.cash_save)) {
        Text(account.name + " · " + account.currency.code)
        Field(stringResource(R.string.cash_current_balance), state.balanceInput, vm::changeBalance, numeric = true,
            enabled = submission.editable, signed = true)
        vm.changePreview()?.let { Text(stringResource(R.string.cash_balance_change, it)) }
        ErrorMessage(submission.error?.name)
    }
}
