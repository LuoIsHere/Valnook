package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as Money
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.UUID

/** One dividend on one account's position: what arrived, net of tax, and on which day. */
data class DividendFormUiState(val positionId: Long?, val tradeId: Long?, val expectedRevision: Long? = null,
    val currency: Currency? = null, val name: String = "", val amountInput: String = "", val date: LocalDate,
    val time: LocalTime = DIVIDEND_TIME, val cashLinked: Boolean = true, val cashAccountId: Long? = null,
    val loaded: Boolean = false, val failed: Boolean = false, val originalAtMs: Long? = null)

/** Dividends are dated by day; at its end they come after anything bought that day. */
internal val DIVIDEND_TIME: LocalTime = LocalTime.of(23, 59)

class DividendFormViewModel(private val accountId: Long, positionId: Long?, tradeId: Long?,
    private val repository: InvestmentRepository, private val instruments: InstrumentRepository, cashRepository: CashRepository,
    commands: FinancialCommands, private val clock: Clock, private val saved: SavedStateHandle) : ViewModel() {
    val isNew = tradeId == null
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    private val mutable = MutableStateFlow(DividendFormUiState(saved["positionId"] ?: positionId, tradeId, saved["revision"],
        saved.get<String>("currency")?.let(Currency::of), saved["name"] ?: "", saved["amount"] ?: "",
        saved.get<String>("date")?.let(LocalDate::parse) ?: LocalDate.now(clock),
        saved.get<String>("time")?.let(LocalTime::parse) ?: DIVIDEND_TIME, saved["linked"] ?: true, saved["cashAccountId"],
        saved["loaded"] ?: false, originalAtMs = saved["originalAt"]))
    val state = mutable.asStateFlow()
    val cashAccounts = cashRepository.observe_cash(accountId).map { rows -> rows.filter { it.type == BalanceAccountType.SAVINGS } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    init { if (!state.value.loaded) load() }

    private fun load() = viewModelScope.launch {
        try {
            val input = state.value
            val trade = input.tradeId?.let { repository.get_trade(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
            if (trade != null && trade.direction != Direction.DIVIDEND) throw DomainException(ErrorCode.NOT_FOUND)
            val asset = (trade?.investment_id ?: input.positionId)?.let { repository.observe_investment(it).first() }
                ?: throw DomainException(ErrorCode.NOT_FOUND)
            if (asset.account_id != accountId) throw DomainException(ErrorCode.NOT_FOUND)
            val instrument = instruments.observeInstrument(asset.instrumentId).first() ?: throw DomainException(ErrorCode.NOT_FOUND)
            val at = trade?.let { Instant.ofEpochMilli(it.occurred_at_ms).atZone(clock.zone) }
            change(input.copy(positionId = asset.id, expectedRevision = trade?.revision, currency = instrument.currency,
                name = instrument.name, amountInput = trade?.let { Money.format_units(it.amount_minor, it.currency.fraction_digits) }
                    ?: input.amountInput, date = at?.toLocalDate() ?: input.date, time = at?.toLocalTime() ?: DIVIDEND_TIME,
                cashLinked = trade?.cash_linked ?: input.cashLinked, cashAccountId = trade?.cashAccountId ?: input.cashAccountId,
                loaded = true, originalAtMs = trade?.occurred_at_ms))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = state.value.copy(failed = true) }
    }

    private fun change(value: DividendFormUiState) {
        mutable.value = value
        saved["positionId"] = value.positionId; saved["revision"] = value.expectedRevision; saved["currency"] = value.currency?.code
        saved["name"] = value.name; saved["amount"] = value.amountInput; saved["date"] = value.date.toString()
        saved["time"] = value.time.toString(); saved["linked"] = value.cashLinked; saved["cashAccountId"] = value.cashAccountId
        saved["loaded"] = value.loaded; saved["originalAt"] = value.originalAtMs
    }
    fun update(transform: (DividendFormUiState) -> DividendFormUiState) { if (submission.value.editable) change(transform(state.value)) }
    /** Linked by default: the first savings account in the currency, until another is chosen. */
    fun ensureCashAccount(candidates: List<CashAccount>) {
        val current = state.value
        if (current.loaded && current.cashLinked && candidates.none { it.id == current.cashAccountId })
            candidates.firstOrNull()?.let { first -> change(current.copy(cashAccountId = first.id)) }
    }
    fun setCashLinked(linked: Boolean) = update { it.copy(cashLinked = linked) }
    fun selectCashAccount(id: Long) = update { it.copy(cashLinked = true, cashAccountId = id) }

    fun submit() = session.submit {
        val input = state.value
        if (!input.loaded) throw DomainException(ErrorCode.NOT_FOUND)
        val currency = requireNotNull(input.currency)
        val amount = Money.parse_units(input.amountInput, currency.fraction_digits)
        val at = keptEpoch(input.date.atTime(input.time), clock.zone, input.originalAtMs)
        val cash = if (input.cashLinked) input.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null
        if (isNew) RecordDividend(operationId, requireNotNull(input.positionId), amount, at, input.cashLinked, cash)
        else EditDividend(operationId, requireNotNull(input.tradeId), requireNotNull(input.expectedRevision), amount, at,
            input.cashLinked, cash)
    }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}

data class DividendToolbarState(val busy: Boolean, val enabled: Boolean, val save: () -> Unit)

@Composable fun DividendSaveAction(state: DividendToolbarState?) {
    val label = stringResource(dev.valnook.core.designsystem.R.string.save)
    ToolbarSaveAction(label, state?.busy == true, state?.enabled == true, { state?.save?.invoke() },
        Modifier.testTag("dividend-save"))
}

@Composable fun dividendFormTitle(isNew: Boolean): String =
    stringResource(if (isNew) R.string.dividend_record_title else R.string.dividend_edit_title)

/** The account and instrument it is for, the amount and day, then whether it reaches cash. */
@Composable fun DividendForm(vm: DividendFormViewModel, accountName: String, onBack: () -> Unit,
    onToolbar: (DividendToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    val cashAccounts by vm.cashAccounts.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val busy = submission.phase == SubmissionPhase.WORKING
    val canSave = state.loaded && !busy && submission.phase != SubmissionPhase.SUCCEEDED && state.amountInput.isNotBlank()
    SideEffect { onToolbar(DividendToolbarState(busy, canSave) { focus.clearFocus(); keyboard?.hide(); vm.submit() }) }
    DisposableEffect(onToolbar) { onDispose { onToolbar(null) } }
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    val candidates = cashAccounts.filter { it.currency == state.currency }
    LaunchedEffect(state.loaded, state.cashLinked, candidates) { vm.ensureCashAccount(candidates) }
    if (!state.loaded || state.failed) {
        Text(stringResource(if (state.failed) R.string.investment_trade_load_failed else R.string.investment_loading))
        return
    }
    val editable = submission.editable
    val currency = requireNotNull(state.currency)
    FormLayout(dividendFormTitle(vm.isNew), busy, canSave, vm::submit, showSaveButton = false) {
        Text(listOf(state.name, accountName).filter { it.isNotBlank() }.joinToString(" · "), Modifier.padding(start = 4.dp)
            .testTag("dividend-subject"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard {
            Column(Modifier.padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledField(stringResource(R.string.dividend_amount, currency.code), state.amountInput,
                    { value -> vm.update { it.copy(amountInput = value) } }, numeric = true, enabled = editable)
                val dateLabel = stringResource(R.string.dividend_date)
                DateField(dateLabel, state.date.toString(), { value -> vm.update { it.copy(date = LocalDate.parse(value)) } }, editable) { open ->
                    FilledChoiceField(dateLabel, state.date.toString(), open, enabled = editable)
                }
            }
        }
        FormSection(null) {
            FormSwitchRow(stringResource(R.string.dividend_link_cash), state.cashLinked, vm::setCashLinked, enabled = editable)
            if (state.cashLinked) {
                FormDivider()
                Column(Modifier.padding(Space.cardInset)) {
                    if (candidates.isEmpty()) Text(stringResource(R.string.investment_cash_missing), color = MaterialTheme.colorScheme.error)
                    else FilledDropdownField(stringResource(R.string.investment_cash_account), state.cashAccountId?.toString().orEmpty(),
                        candidates.map { it.id.toString() to (it.name + " · " + Money.format_display(it.balance_minor, it.currency.fraction_digits) +
                            " " + it.currency.code) }, { vm.selectCashAccount(it.toLong()) }, enabled = editable)
                }
            }
        }
        Text(stringResource(R.string.dividend_hint), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (submission.error) {
            ErrorCode.HISTORY_CONFLICT -> Text(stringResource(R.string.dividend_history_conflict), color = MaterialTheme.colorScheme.error)
            else -> ErrorMessage(submission.error?.name)
        }
        if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.investment_unknown_result))
    }
}
