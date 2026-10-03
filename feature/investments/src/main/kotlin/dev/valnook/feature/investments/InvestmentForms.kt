package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.*
import java.time.*

@Composable fun PositionCreateForm(vm: PositionCreateViewModel, onBack: () -> Unit) {
    val submission by vm.submission.collectAsStateWithLifecycle()
    val instruments by vm.available.collectAsStateWithLifecycle()
    val selectedInstrumentId by vm.selectedInstrumentId.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    FormLayout(stringResource(R.string.investment_add_to_account),
        submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED && selectedInstrumentId != null,
        vm::submit) {
        if (instruments.isEmpty()) Text(stringResource(R.string.investment_no_available_instruments))
        else ChoiceField(stringResource(R.string.investment_instrument),
            selectedInstrumentId?.toString().orEmpty(),
            instruments.map { it.id.toString() to (it.name + " · " + it.symbol) },
            { id -> instruments.firstOrNull { it.id.toString() == id }?.let(vm::select) }, submission.editable)
        ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.investment_unknown_result))
    }
}

@Composable fun TradeForm(vm: TradeFormViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val cashAccounts by vm.cashAccounts.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded || state.failed) {
        Text(stringResource(if (state.failed) R.string.investment_trade_load_failed else R.string.investment_loading))
        return
    }
    val title = stringResource(when (state.mode) {
        TradeFormMode.CREATE -> if (state.direction == Direction.BUY) R.string.investment_buy else R.string.investment_sell
        TradeFormMode.EDIT -> R.string.investment_edit_trade
        TradeFormMode.DELETE -> R.string.investment_delete_trade
    })
    FormLayout(title, submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED,
        vm::submit, if (submission.phase == SubmissionPhase.UNKNOWN) stringResource(R.string.investment_review_retry)
        else if (state.mode == TradeFormMode.DELETE) stringResource(R.string.investment_confirm_delete)
        else stringResource(R.string.investment_save)) {
        Text(state.name)
        if (state.mode == TradeFormMode.DELETE) Text(stringResource(R.string.investment_delete_hint))
        else {
            if (state.mode == TradeFormMode.EDIT) ChoiceField(stringResource(R.string.investment_trade_direction), state.direction.name,
                listOf(Direction.BUY.name to stringResource(R.string.investment_buy),
                    Direction.SELL.name to stringResource(R.string.investment_sell)),
                { value -> vm.update { it.copy(direction = Direction.valueOf(value)) } }, submission.editable)
            Field(stringResource(R.string.investment_quantity), state.quantityInput,
                { value -> vm.update { it.copy(quantityInput = value) } }, true, submission.editable)
            Field(stringResource(R.string.investment_execution_price),
                state.executionPriceInput, { value -> vm.update { it.copy(executionPriceInput = value) } }, true,
                submission.editable)
            if (state.mode == TradeFormMode.CREATE || state.mode == TradeFormMode.EDIT)
                Field(stringResource(R.string.investment_trade_fee), state.feeInput,
                    { value -> vm.update { it.copy(feeInput = value) } }, true, submission.editable)
            DateField(stringResource(R.string.investment_record_date), state.occurredAt.toLocalDate().toString(), { value -> vm.update {
                it.copy(occurredAt = LocalDateTime.of(LocalDate.parse(value), it.occurredAt.toLocalTime()))
            } }, submission.editable)
            TimeField(stringResource(R.string.investment_record_time), state.occurredAt.toLocalTime().toString(), { value -> vm.update {
                it.copy(occurredAt = LocalDateTime.of(it.occurredAt.toLocalDate(), LocalTime.parse(value)))
            } }, submission.editable)
            if (state.mode == TradeFormMode.CREATE || state.mode == TradeFormMode.EDIT) {
                vm.amountPreview()?.let { Text(stringResource(R.string.investment_trade_amount, it)) }
                CheckboxRow(stringResource(R.string.investment_link_cash, state.currency?.code.orEmpty()), state.cashLinked,
                    vm::setCashLinked, enabled = submission.editable)
                if (state.cashLinked) {
                    val candidates = cashAccounts.filter { it.currency == state.currency }
                    if (candidates.isEmpty()) Text(stringResource(R.string.investment_cash_missing), color = MaterialTheme.colorScheme.error)
                    else ChoiceField(stringResource(R.string.investment_cash_account), state.cashAccountId?.toString().orEmpty(),
                        candidates.map { it.id.toString() to (it.name + " · " + it.currency.code + " · " +
                            dev.valnook.domain.money.DecimalRules.format_display(it.balance_minor, it.currency.fraction_digits)) },
                        { vm.selectCashAccount(it.toLong()) }, submission.editable)
                }
            }
        }
        if (state.mode == TradeFormMode.CREATE || state.mode == TradeFormMode.EDIT || state.mode == TradeFormMode.DELETE)
            vm.cashImpactPreview()?.let { Text(stringResource(R.string.investment_cash_change, it)) }
        if (submission.error == ErrorCode.HISTORY_CONFLICT)
            Text(stringResource(R.string.investment_history_conflict), color = MaterialTheme.colorScheme.error)
        else ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.investment_unknown_result))
    }
}
@Composable fun InstrumentEditScreen(vm: InstrumentEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val typeState by vm.types.collectAsStateWithLifecycle()
    val types = (typeState as? AssetTypesState.Ready)?.rows.orEmpty()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) { Text(stringResource(if (state.loadFailed) R.string.instrument_load_failed else R.string.instrument_loading))
        return }
    FormLayout(stringResource(R.string.instrument_edit_title), submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED && types.isNotEmpty(), vm::submit) {
        Field(stringResource(R.string.instrument_name), state.name, { value -> vm.update { it.copy(name = value) } }, enabled = submission.editable)
        when {
            typeState == AssetTypesState.Loading -> Text(stringResource(R.string.instrument_types_loading))
            typeState == AssetTypesState.Failed -> Text(stringResource(R.string.instrument_types_failed), color = MaterialTheme.colorScheme.error)
            types.isEmpty() -> Text(stringResource(R.string.instrument_type_required), color = MaterialTheme.colorScheme.error)
        }
        Field(stringResource(R.string.instrument_symbol), state.symbol, { value -> vm.update { it.copy(symbol = value) } },
            enabled = submission.editable && !state.symbolLocked)
        ChoiceField(stringResource(R.string.instrument_asset_class), state.typeId?.toString().orEmpty(), types.map { it.id.toString() to it.name },
            { value -> vm.update { it.copy(typeId = value.toLong()) } }, submission.editable)
        CurrencyChoice(state.currency.code, { value -> vm.update {
            it.copy(currency = Currency.of(value), currencyPriceConfirmed = false)
        } }, submission.editable && !state.currencyLocked, Currency.supported.map { it.code to it.name })
        Field(stringResource(R.string.instrument_current_price), state.priceInput,
            { value -> vm.update { it.copy(priceInput = value) } }, true, submission.editable)
        if (!state.currencyLocked && state.expectedRevision != null)
            CheckboxRow(stringResource(R.string.instrument_confirm_currency_price), state.currencyPriceConfirmed,
                { value -> vm.update { it.copy(currencyPriceConfirmed = value) } }, enabled = submission.editable)
        when (submission.error) {
            ErrorCode.CURRENCY_LOCKED -> Text(stringResource(R.string.instrument_currency_locked_error), color = MaterialTheme.colorScheme.error)
            ErrorCode.SYMBOL_LOCKED -> Text(stringResource(R.string.instrument_symbol_locked_error), color = MaterialTheme.colorScheme.error)
            ErrorCode.PRICE_CONFIRMATION -> Text(stringResource(R.string.instrument_price_confirmation_error), color = MaterialTheme.colorScheme.error)
            else -> ErrorMessage(submission.error?.name)
        }
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.investment_unknown_result))
    }
}
@Composable fun InstrumentPriceEditScreen(vm: InstrumentEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) {
        Text(stringResource(if (state.loadFailed) R.string.instrument_load_failed else R.string.instrument_loading))
        return
    }
    FormLayout(stringResource(R.string.instrument_price_edit_title),
        submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        Text(listOf(state.name, state.symbol, state.currency.code).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.instrument_price_shared_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Field(stringResource(R.string.instrument_current_price), state.priceInput,
            { value -> vm.update { it.copy(priceInput = value) } }, true, submission.editable)
        ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN)
            Text(stringResource(R.string.investment_unknown_result))
    }
}
@Composable fun TypeEditScreen(vm: TypeEditViewModel, onBack: () -> Unit) {
    val name by vm.name.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    FormLayout(stringResource(R.string.instrument_type_title), submission.phase == SubmissionPhase.WORKING, submission.phase != SubmissionPhase.SUCCEEDED, vm::submit) {
        Field(stringResource(R.string.instrument_name), name, vm::updateName, enabled = submission.editable)
        ErrorMessage(submission.error?.name)
    }
}
