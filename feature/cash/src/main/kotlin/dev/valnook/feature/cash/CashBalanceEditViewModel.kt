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
    val failed: Boolean = false
)

class CashBalanceEditViewModel(
    private val parentAccountId: Long,
    private val cashAccountId: Long,
    repository: CashRepository,
    commands: FinancialCommands,
    private val saved: SavedStateHandle
) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also {
        saved["operationId"] = it
    }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) {
        saved["submission"] = it.name
    }
    val submission = session.state
    private val mutable = MutableStateFlow(CashBalanceEditState(balanceInput = saved["balance"] ?: "",
        loaded = saved["loaded"] ?: false))
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val account = repository.observeCashAccount(cashAccountId).first()
                    ?.takeIf { it.account_id == parentAccountId } ?: throw DomainException(ErrorCode.NOT_FOUND)
                val input = saved.get<String>("balance") ?: R.format_units(account.balance_minor,
                    account.currency.fraction_digits)
                mutable.value = CashBalanceEditState(account, input, true)
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

    fun changePreview(): String? = runCatching {
        val current = requireNotNull(state.value.account)
        val target = R.parse_signed_minor(state.value.balanceInput, current.currency)
        val delta = R.replace_contribution(target, current.balance_minor, 0)
        (if (delta > 0) "+" else "") + R.format_units(delta, current.currency.fraction_digits) +
            " " + current.currency.code
    }.getOrNull()

    fun submit() = session.submit {
        val current = state.value.account ?: throw DomainException(ErrorCode.NOT_FOUND)
        SetCashBalance(operationId, parentAccountId, current.currency.code,
            R.parse_signed_minor(state.value.balanceInput, current.currency), current.revision,
            current.id, current.name, current.note)
    }

    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
