package dev.valnook.feature.accounts

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class CashBalanceRowDraft(val key: String, val currency: Currency, val balanceInput: String,
    val savedBalanceMinor: Long? = null, val expectedRevision: Long? = null) {
    val locked: Boolean get() = expectedRevision != null
}
data class AccountEditUiState(val name: String = "", val note: String = "",
    val rows: List<CashBalanceRowDraft> = emptyList(), val expectedRevision: Long? = null,
    val loaded: Boolean = false, val loadError: Boolean = false)

class AccountEditViewModel(private val accountId: Long?, private val repository: OverviewRepository,
    commands: FinancialCommands, private val saved: SavedStateHandle) : ViewModel() {
    val operationId: String = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    private val mutable = MutableStateFlow(restore())
    val state = mutable.asStateFlow()
    init { if (!state.value.loaded) reload() }

    private fun restore(): AccountEditUiState {
        if (saved.get<Boolean>("loaded") != true) return AccountEditUiState()
        val keys = saved.get<ArrayList<String>>("rowKeys").orEmpty()
        return AccountEditUiState(saved["name"] ?: "", saved["note"] ?: "", keys.map { key ->
            CashBalanceRowDraft(key, Currency.of(requireNotNull(saved["currency-$key"])),
                saved["balance-$key"] ?: "", saved["before-$key"], saved["revision-$key"])
        }, saved["accountRevision"], true)
    }
    private fun persist(value: AccountEditUiState) {
        val activeKeys = value.rows.map { it.key }.toSet()
        state.value.rows.filter { it.key !in activeKeys }.forEach { row ->
            listOf("currency", "balance", "before", "revision").forEach { prefix ->
                saved.remove<Any>("$prefix-${row.key}")
            }
        }
        mutable.value = value
        saved["loaded"] = value.loaded
        saved["name"] = value.name
        saved["note"] = value.note
        saved["accountRevision"] = value.expectedRevision
        saved["rowKeys"] = ArrayList(value.rows.map { it.key })
        value.rows.forEach {
            saved["currency-${it.key}"] = it.currency.code
            saved["balance-${it.key}"] = it.balanceInput
            saved["before-${it.key}"] = it.savedBalanceMinor
            saved["revision-${it.key}"] = it.expectedRevision
        }
    }
    fun reload() {
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot()
                val account = accountId?.let { id -> snapshot.accounts.firstOrNull { it.id == id } ?: throw DomainException(ErrorCode.NOT_FOUND) }
                persist(AccountEditUiState(account?.name.orEmpty(), account?.note.orEmpty(),
                    snapshot.cash.filter { it.account_id == accountId }.map {
                        CashBalanceRowDraft(it.currency.code, it.currency, R.format_units(it.balance_minor, it.currency.fraction_digits),
                            it.balance_minor, it.revision)
                    }, account?.revision, true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(loadError = true) }
        }
    }
    fun changeName(value: String) { if (submission.value.editable) persist(state.value.copy(name = value)) }
    fun changeNote(value: String) { if (submission.value.editable) persist(state.value.copy(note = value)) }
    fun changeRow(key: String, currency: Currency? = null, balance: String? = null) {
        if (!submission.value.editable) return
        persist(state.value.copy(rows = state.value.rows.map {
            if (it.key != key) it else it.copy(currency = if (it.locked) it.currency else currency ?: it.currency,
                balanceInput = balance ?: it.balanceInput)
        }))
    }
    fun addRow() {
        if (!submission.value.editable) return
        val currency = Currency.supported.firstOrNull { candidate -> state.value.rows.none { it.currency == candidate } } ?: return
        persist(state.value.copy(rows = state.value.rows + CashBalanceRowDraft(UUID.randomUUID().toString(), currency, "")))
    }
    fun removeRow(key: String) {
        if (submission.value.editable) persist(state.value.copy(rows = state.value.rows.filter { it.key != key || it.locked }))
    }
    fun submit() = session.submit {
        val input = state.value
        if (!input.loaded) throw DomainException(ErrorCode.NOT_FOUND)
        val changes = input.rows.mapNotNull { row ->
            val amount = R.parse_minor(row.balanceInput, row.currency)
            if (amount == row.savedBalanceMinor) null else CashBalanceChange(row.currency.code, amount, row.expectedRevision)
        }
        if (input.rows.distinctBy { it.currency.code }.size != input.rows.size) throw DomainException(ErrorCode.DUPLICATE_CURRENCY)
        SaveAccount(operationId, accountId, input.expectedRevision, input.name, input.note, changes)
    }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
