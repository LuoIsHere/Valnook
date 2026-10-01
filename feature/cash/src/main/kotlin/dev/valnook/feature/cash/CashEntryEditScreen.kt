package dev.valnook.feature.cash

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import java.time.*

@Composable fun CashEntryEditScreen(vm: CashEntryEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(if (state.failed) "记录不可用" else "正在读取")
        return }
    FormLayout("修改余额变化", submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        Text(state.currency?.code.orEmpty())
        ChoiceField("增减方向", state.direction.name, CashChangeDirection.entries.map { it.name to if (it == CashChangeDirection.INCREASE) "增加" else "减少" },
            { value -> vm.update { it.copy(direction = CashChangeDirection.valueOf(value)) } }, submission.editable)
        Field("变化金额", state.amountInput, { value -> vm.update { it.copy(amountInput = value) } }, true, submission.editable)
        DateField("记账日期", state.occurredAt.toLocalDate().toString(), { value -> vm.update {
            it.copy(occurredAt = LocalDateTime.of(LocalDate.parse(value), it.occurredAt.toLocalTime()))
        } }, submission.editable)
        TimeField("记账时间", state.occurredAt.toLocalTime().toString(), { value -> vm.update {
            it.copy(occurredAt = LocalDateTime.of(it.occurredAt.toLocalDate(), LocalTime.parse(value)))
        } }, submission.editable)
        Field("备注", state.note, { value -> vm.update { it.copy(note = value) } }, enabled = submission.editable)
        Text("修正这条凭据对余额的影响；设置当前余额请使用编辑账户。")
        vm.changePreview()?.let { Text("本次余额修正 " + it) }
        ErrorMessage(submission.error?.name)
    }
}
