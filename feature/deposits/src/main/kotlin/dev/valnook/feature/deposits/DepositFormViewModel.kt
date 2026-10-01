package dev.valnook.feature.deposits

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.UUID

enum class DepositFormMode { CREATE, EDIT, CLOSE }
data class DepositFormUiState(val currency: Currency, val principalInput: String, val rateInput: String,
    val startDate: LocalDate, val endDate: LocalDate, val cashLinked: Boolean,
    val closeCashLinked: Boolean?, val revision: Long?, val cashAccountId: Long? = null,
    val closeCashAccountId: Long? = null, val loaded: Boolean = false, val failed: Boolean = false,
    val originalCashContributionMinor: Long = 0)
class DepositFormViewModel(private val accountId: Long, val mode: DepositFormMode, private val depositId: Long?,
    repository: DepositRepository, cashRepository: CashRepository, commands: FinancialCommands, private val clock: Clock,
    private val saved: SavedStateHandle) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    private val mutable = MutableStateFlow(DepositFormUiState(Currency.of(saved["currency"] ?: "CNY"),
        saved["principal"] ?: "", saved["rate"] ?: "",
        saved.get<String>("start")?.let(LocalDate::parse) ?: LocalDate.now(clock),
        saved.get<String>("end")?.let(LocalDate::parse) ?: LocalDate.now(clock).plusMonths(3),
        saved["linked"] ?: false, saved["closeLinked"], saved["revision"], saved["cashAccountId"],
        saved["closeCashAccountId"], saved["loaded"] ?: false,
        originalCashContributionMinor = saved["originalCashContribution"] ?: 0))
    val state = mutable.asStateFlow()
    val cashAccounts = cashRepository.observe_cash(accountId).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    init {
        if (!state.value.loaded) viewModelScope.launch {
            try {
                val deposit = depositId?.let { repository.get_deposit(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
                if (deposit != null && deposit.account_id != accountId) throw DomainException(ErrorCode.NOT_FOUND)
                updateInternal(if (deposit == null) state.value.copy(loaded = true) else DepositFormUiState(deposit.currency,
                    R.format_units(deposit.principal_minor, deposit.currency.fraction_digits),
                    R.format_e8(deposit.annual_rate_percent_e8), LocalDate.ofEpochDay(deposit.start_epoch_day),
                    LocalDate.ofEpochDay(deposit.end_epoch_day), if (mode == DepositFormMode.CLOSE) false else deposit.open_cash_linked,
                    deposit.close_cash_linked, deposit.revision, deposit.openCashAccountId,
                    deposit.closeCashAccountId, true,
                    originalCashContributionMinor = if (mode == DepositFormMode.CLOSE) 0 else R.add(
                        if (deposit.open_cash_linked) -deposit.principal_minor else 0,
                        if (deposit.closed && deposit.close_cash_linked == true)
                            R.add(deposit.principal_minor, deposit.expected_interest_minor) else 0)))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(failed = true) }
        }
    }
    private fun updateInternal(value: DepositFormUiState) {
        mutable.value = value
        saved["currency"] = value.currency.code
        saved["principal"] = value.principalInput
        saved["rate"] = value.rateInput
        saved["start"] = value.startDate.toString()
        saved["end"] = value.endDate.toString()
        saved["linked"] = value.cashLinked
        saved["closeLinked"] = value.closeCashLinked
        saved["revision"] = value.revision
        saved["cashAccountId"] = value.cashAccountId
        saved["closeCashAccountId"] = value.closeCashAccountId
        saved["loaded"] = value.loaded
        saved["originalCashContribution"] = value.originalCashContributionMinor
    }
    fun update(transform: (DepositFormUiState) -> DepositFormUiState) {
        if (submission.value.editable) updateInternal(transform(state.value))
    }
    fun setOpenCashLinked(linked: Boolean) = update { current ->
        val candidates = cashAccounts.value.filter { it.currency == current.currency }
        current.copy(cashLinked = linked,
            cashAccountId = if (!linked) null else current.cashAccountId ?: candidates.singleOrNull()?.id)
    }
    fun selectOpenCashAccount(id: Long) = update { it.copy(cashLinked = true, cashAccountId = id) }
    fun setCloseCashLinked(linked: Boolean) = update { current ->
        val candidates = cashAccounts.value.filter { it.currency == current.currency }
        current.copy(closeCashLinked = linked,
            closeCashAccountId = if (!linked) null else current.closeCashAccountId ?: candidates.singleOrNull()?.id)
    }
    fun selectCloseCashAccount(id: Long) = update { it.copy(closeCashLinked = true, closeCashAccountId = id) }
    fun preview(): String? = runCatching {
        val input = state.value
        R.format_units(R.interest(R.parse_minor(input.principalInput, input.currency, true), R.parse_e8(input.rateInput),
            input.startDate.toEpochDay(), input.endDate.toEpochDay()), input.currency.fraction_digits)
    }.getOrNull()
    fun cashImpactPreview(): String? = runCatching {
        val input = state.value
        val principal = R.parse_minor(input.principalInput, input.currency, true)
        val interest = R.interest(principal, R.parse_e8(input.rateInput), input.startDate.toEpochDay(), input.endDate.toEpochDay())
        val nextContribution = if (mode == DepositFormMode.CLOSE)
            if (input.cashLinked) R.add(principal, interest) else 0L
        else R.add(if (input.cashLinked) -principal else 0L,
            if (input.closeCashLinked == true) R.add(principal, interest) else 0L)
        val delta = R.replace_contribution(0, input.originalCashContributionMinor, nextContribution)
        (if (delta > 0) "+" else "") + R.format_units(delta, input.currency.fraction_digits) + " " + input.currency.code
    }.getOrNull()
    fun submit() = session.submit {
        val input = state.value
        when (mode) {
            DepositFormMode.CREATE -> OpenTermDeposit(operationId, accountId, input.currency.code,
                R.parse_minor(input.principalInput, input.currency, true), R.parse_e8(input.rateInput),
                input.startDate.toEpochDay(), input.endDate.toEpochDay(), input.cashLinked,
                if (input.cashLinked) input.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null)
            DepositFormMode.EDIT -> EditTermDeposit(operationId, requireNotNull(depositId), requireNotNull(input.revision),
                R.parse_minor(input.principalInput, input.currency, true), R.parse_e8(input.rateInput),
                input.startDate.toEpochDay(), input.endDate.toEpochDay(), input.cashLinked, input.closeCashLinked,
                if (input.cashLinked) input.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null,
                if (input.closeCashLinked == true) input.closeCashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null)
            DepositFormMode.CLOSE -> CloseTermDeposit(operationId, requireNotNull(depositId), input.cashLinked,
                if (input.cashLinked) input.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null)
        }
    }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
