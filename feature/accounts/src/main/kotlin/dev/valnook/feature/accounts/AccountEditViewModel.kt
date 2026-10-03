package dev.valnook.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.command.SubmissionSession
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.OverviewRepository
import dev.valnook.domain.repository.SaveAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class CashAccountRowDraft(
    val key: String,
    val cashAccountId: Long?,
    val nameInput: String,
    val noteInput: String,
    val currency: Currency,
    val balanceInput: String,
    val savedBalanceMinor: Long? = null,
    val expectedRevision: Long? = null
) {
    val currencyLocked: Boolean get() = cashAccountId != null
}

data class AccountEditUiState(
    val name: String = "",
    val note: String = "",
    val rows: List<CashAccountRowDraft> = emptyList(),
    val expectedRevision: Long? = null,
    val loaded: Boolean = false,
    val loadError: Boolean = false
)

class AccountEditViewModel(
    private val accountId: Long?,
    private val repository: OverviewRepository,
    commands: FinancialCommands,
    private val saved: SavedStateHandle
) : ViewModel() {
    val operationId: String = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also {
        saved["operationId"] = it
    }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) {
        saved["submission"] = it.name
    }
    val submission = session.state
    private val mutable = MutableStateFlow(restore())
    val state = mutable.asStateFlow()

    init {
        if (!state.value.loaded) reload()
    }

    private fun restore(): AccountEditUiState {
        if (saved.get<Boolean>("loaded") != true) return AccountEditUiState()
        val keys = saved.get<ArrayList<String>>("rowKeys").orEmpty()
        return AccountEditUiState(saved["name"] ?: "", saved["note"] ?: "", keys.map { key ->
            CashAccountRowDraft(key, saved["cashId-$key"], saved["cashName-$key"] ?: "",
                saved["cashNote-$key"] ?: "", Currency.of(requireNotNull(saved["currency-$key"])),
                saved["balance-$key"] ?: "", saved["before-$key"], saved["revision-$key"])
        }, saved["accountRevision"], true)
    }

    private fun persist(value: AccountEditUiState) {
        val activeKeys = value.rows.map { it.key }.toSet()
        state.value.rows.filter { it.key !in activeKeys }.forEach { row ->
            listOf("cashId", "cashName", "cashNote", "currency", "balance", "before", "revision").forEach {
                prefix -> saved.remove<Any>("$prefix-${row.key}")
            }
        }
        mutable.value = value
        saved["loaded"] = value.loaded
        saved["name"] = value.name
        saved["note"] = value.note
        saved["accountRevision"] = value.expectedRevision
        saved["rowKeys"] = ArrayList(value.rows.map { it.key })
        value.rows.forEach { row ->
            saved["cashId-${row.key}"] = row.cashAccountId
            saved["cashName-${row.key}"] = row.nameInput
            saved["cashNote-${row.key}"] = row.noteInput
            saved["currency-${row.key}"] = row.currency.code
            saved["balance-${row.key}"] = row.balanceInput
            saved["before-${row.key}"] = row.savedBalanceMinor
            saved["revision-${row.key}"] = row.expectedRevision
        }
    }

    fun reload() {
        viewModelScope.launch {
            try {
                val snapshot = repository.snapshot()
                val account = accountId?.let { id ->
                    snapshot.accounts.firstOrNull { it.id == id } ?: throw DomainException(ErrorCode.NOT_FOUND)
                }
                persist(AccountEditUiState(account?.name.orEmpty(), account?.note.orEmpty(),
                    snapshot.cash.filter { it.account_id == accountId }.map { cash ->
                        CashAccountRowDraft(cash.id.toString(), cash.id, cash.name, cash.note, cash.currency,
                            R.format_units(cash.balance_minor, cash.currency.fraction_digits),
                            cash.balance_minor, cash.revision)
                    }, account?.revision, true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(loadError = true)
            }
        }
    }

    fun changeName(value: String) {
        if (submission.value.editable) persist(state.value.copy(name = value))
    }

    fun changeNote(value: String) {
        if (submission.value.editable) persist(state.value.copy(note = value))
    }

    fun changeRow(
        key: String,
        name: String? = null,
        note: String? = null,
        currency: Currency? = null,
        balance: String? = null
    ) {
        if (!submission.value.editable) return
        persist(state.value.copy(rows = state.value.rows.map { row ->
            if (row.key != key) row else {
                val nextCurrency = if (row.currencyLocked) row.currency else currency ?: row.currency
                val nextName = when {
                    name != null -> name
                    currency != null && !row.currencyLocked && row.nameInput == row.currency.code -> nextCurrency.code
                    else -> row.nameInput
                }
                row.copy(nameInput = nextName, noteInput = note ?: row.noteInput,
                    currency = nextCurrency, balanceInput = balance ?: row.balanceInput)
            }
        }))
    }

    fun addRow() {
        if (!submission.value.editable) return
        val currency = Currency.supported.first()
        persist(state.value.copy(rows = state.value.rows + CashAccountRowDraft(
            key = UUID.randomUUID().toString(), cashAccountId = null, nameInput = currency.code,
            noteInput = "", currency = currency, balanceInput = "0")))
    }

    fun removeRow(key: String) {
        if (!submission.value.editable) return
        persist(state.value.copy(rows = state.value.rows.filter { it.key != key || it.cashAccountId != null }))
    }

    fun submit() = session.submit {
        val input = state.value
        if (!input.loaded) throw DomainException(ErrorCode.NOT_FOUND)
        val changes = input.rows.map { row ->
            CashBalanceChange(row.currency.code, R.parse_signed_minor(row.balanceInput, row.currency),
                row.expectedRevision, row.cashAccountId, row.nameInput, row.noteInput)
        }
        SaveAccount(operationId, accountId, input.expectedRevision, input.name, input.note, changes)
    }

    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
