package dev.valnook.feature.cash

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import java.time.*

@Composable fun CashEntryEditScreen(vm: CashEntryEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(stringResource(if (state.failed) R.string.cash_record_unavailable else R.string.cash_loading))
        return }
    FormLayout(stringResource(R.string.cash_edit_change), submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        Text(state.currency?.code.orEmpty())
        ChoiceField(stringResource(R.string.cash_change_direction), state.direction.name, CashChangeDirection.entries.map {
            it.name to stringResource(if (it == CashChangeDirection.INCREASE) R.string.cash_increase else R.string.cash_decrease) },
            { value -> vm.update { it.copy(direction = CashChangeDirection.valueOf(value)) } }, submission.editable)
        Field(stringResource(R.string.cash_change_amount), state.amountInput, { value -> vm.update { it.copy(amountInput = value) } }, true, submission.editable)
        DateField(stringResource(R.string.cash_record_date), state.occurredAt.toLocalDate().toString(), { value -> vm.update {
            it.copy(occurredAt = LocalDateTime.of(LocalDate.parse(value), it.occurredAt.toLocalTime()))
        } }, submission.editable)
        TimeField(stringResource(R.string.cash_record_time), state.occurredAt.toLocalTime().toString(), { value -> vm.update {
            it.copy(occurredAt = LocalDateTime.of(it.occurredAt.toLocalDate(), LocalTime.parse(value)))
        } }, submission.editable)
        Field(stringResource(R.string.cash_note), state.note, { value -> vm.update { it.copy(note = value) } }, enabled = submission.editable)
        Text(stringResource(R.string.cash_edit_entry_hint))
        vm.changePreview()?.let { Text(stringResource(R.string.cash_correction_preview, it)) }
        ErrorMessage(submission.error?.name)
    }
}
