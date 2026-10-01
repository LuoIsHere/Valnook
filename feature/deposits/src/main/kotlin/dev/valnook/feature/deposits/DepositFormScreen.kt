package dev.valnook.feature.deposits

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.Currency
import java.time.LocalDate

@Composable fun DepositForm(vm: DepositFormViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(if (state.failed) "存单读取失败" else "正在读取")
        return }
    val title = when (vm.mode) { DepositFormMode.CREATE -> "开立存单"
        DepositFormMode.EDIT -> "修改存单"
        DepositFormMode.CLOSE -> "结算存单" }
    FormLayout(title, submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        CurrencyChoice(state.currency.code, { value -> vm.update { it.copy(currency = Currency.of(value)) } },
            submission.editable && vm.mode == DepositFormMode.CREATE, Currency.supported.map { it.code to it.name })
        if (vm.mode != DepositFormMode.CLOSE) {
            Field("本金", state.principalInput, { value -> vm.update { it.copy(principalInput = value) } }, true, submission.editable)
            Field("年利率（%）", state.rateInput, { value -> vm.update { it.copy(rateInput = value) } }, true, submission.editable)
            DateField("开始日期", state.startDate.toString(), { value -> vm.update { it.copy(startDate = LocalDate.parse(value)) } }, submission.editable)
            DateField("结束日期", state.endDate.toString(), { value -> vm.update { it.copy(endDate = LocalDate.parse(value)) } }, submission.editable)
        } else Text("本金 " + state.principalInput + " " + state.currency.code)
        Text("预计利息 " + (vm.preview() ?: "—") + " " + state.currency.code)
        CheckboxRow(if (vm.mode == DepositFormMode.CLOSE) "结算回款联动现金" else "开立扣款联动现金",
            state.cashLinked, { value -> vm.update { it.copy(cashLinked = value) } }, enabled = submission.editable)
        if (vm.mode == DepositFormMode.EDIT && state.closeCashLinked != null)
            CheckboxRow("结算回款联动现金", state.closeCashLinked == true,
                { value -> vm.update { it.copy(closeCashLinked = value) } }, enabled = submission.editable)
        Text("仅勾选的现金联动会改变本账户对应币种余额")
        vm.cashImpactPreview()?.let { Text("本次该账户现金变化 " + it) }
        ErrorMessage(submission.error?.name)
    }
}
