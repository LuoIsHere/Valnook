package dev.valnook.feature.cash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.command.SubmissionSession
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.CashRepository
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.SetCashBalance
import dev.valnook.domain.repository.BillRepository
import dev.valnook.domain.repository.SaveBill
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillType
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

data class CashBalanceEditState(
    val account: CashAccount? = null,
    val balanceInput: String = "",
    val loaded: Boolean = false,
    val failed: Boolean = false,
    val entryNote: String = "",
    /** Record the change as an expense or income, shown on the bills page, instead of a plain adjustment. */
    val countInStats: Boolean = false,
    /** Whether bills can be recorded here at all (not in tests or graphs without bills). */
    val billsAvailable: Boolean = false,
)

class CashBalanceEditViewModel(
    private val parentAccountId: Long,
    private val cashAccountId: Long,
    repository: CashRepository,
    commands: FinancialCommands,
    private val saved: SavedStateHandle,
    private val bills: BillRepository? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** The note a counted adjustment's bill gets when none is typed, in the language of the day it is saved. */
    private val billNote: String = "",
) : ViewModel() {
    /** "Other › uncategorised" on each side, where a counted adjustment goes; the bill can be moved later. */
    private var expenseCategory: Pair<Long, Long?>? = null
    private var incomeCategory: Pair<Long, Long?>? = null
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also {
        saved["operationId"] = it
    }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) {
        saved["submission"] = it.name
    }
    val submission = session.state
    private val mutable = MutableStateFlow(CashBalanceEditState(balanceInput = saved["balance"] ?: "",
        loaded = false))
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val account = repository.observeCashAccount(cashAccountId).first()
                    ?.takeIf { it.account_id == parentAccountId } ?: throw DomainException(ErrorCode.NOT_FOUND)
                val input = saved.get<String>("balance") ?: R.format_units(account.balance_minor,
                    account.currency.fraction_digits)
                bills?.let { repository ->
                    val categories = repository.observeCategories().first()
                    fun preset(parent: String, child: String) = categories.firstOrNull { it.builtinKey == parent }?.id
                        ?.let { it to categories.firstOrNull { c -> c.builtinKey == child }?.id }
                    expenseCategory = preset("other", "other.uncategorized")
                    incomeCategory = preset("income.other", "income.other.uncategorized")
                }
                mutable.value = CashBalanceEditState(account, input, true, entryNote = saved["entryNote"] ?: "",
                    countInStats = saved["countInStats"] ?: false,
                    billsAvailable = expenseCategory != null && incomeCategory != null)
                saved["balance"] = input
                saved["loaded"] = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(failed = true)
            }
        }
    }

    fun changeBalance(value: String) {
        if (!submission.value.editable) return
        mutable.value = state.value.copy(balanceInput = value)
        saved["balance"] = value
    }

    fun changeNote(value: String) {
        if (!submission.value.editable || value.length > 2000) return
        mutable.value = state.value.copy(entryNote = value)
        saved["entryNote"] = value
    }

    fun changeCountInStats(value: Boolean) {
        if (!submission.value.editable) return
        mutable.value = state.value.copy(countInStats = value)
        saved["countInStats"] = value
    }

    fun changePreview(): String? = runCatching {
        val current = requireNotNull(state.value.account)
        val target = R.parse_signed_minor(state.value.balanceInput, current.currency)
        val delta = R.replace_contribution(target, current.balance_minor, 0)
        (if (delta > 0) "+" else "") + R.format_units(delta, current.currency.fraction_digits) +
            " " + current.currency.code
    }.getOrNull()

    fun submit() = session.submit {
        val current = state.value.account ?: throw DomainException(ErrorCode.NOT_FOUND)
        val target = R.parse_signed_minor(state.value.balanceInput, current.currency)
        val delta = R.replace_contribution(target, current.balance_minor, 0)
        // Counted, the change becomes a bill on this sub-account: money out an expense, money in an income.
        val category = if (delta < 0) expenseCategory else incomeCategory
        if (state.value.countInStats && state.value.billsAvailable && delta != 0L && category != null) {
            val amount = kotlin.math.abs(delta)
            return@submit SaveBill(operationId, null, null, BillDraft(if (delta < 0) BillType.EXPENSE else BillType.INCOME,
                clock.millis(), amount, current.currency.code, 0, current.id, amount, categoryId = category.first,
                subCategoryId = category.second, countInStats = true,
                note = state.value.entryNote.takeIf { it.isNotBlank() } ?: billNote))
        }
        SetCashBalance(operationId, parentAccountId, current.currency.code,
            R.parse_signed_minor(state.value.balanceInput, current.currency), current.revision,
            current.id, current.name, current.note, state.value.entryNote)
    }

    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
