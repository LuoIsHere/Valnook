package dev.valnook.feature.cash

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.UUID

enum class CashChangeDirection { INCREASE, DECREASE }
data class CashEntryDraft(val currency: Currency?, val amountInput: String, val direction: CashChangeDirection,
    val occurredAt: LocalDateTime, val note: String, val revision: Long?, val loaded: Boolean = false,
    val failed: Boolean = false, val originalDeltaMinor: Long = 0)
class CashEntryEditViewModel(private val accountId: Long, private val entryId: Long, repository: CashRepository,
    commands: FinancialCommands, private val clock: Clock, private val saved: SavedStateHandle) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    private val mutable = MutableStateFlow(CashEntryDraft(saved.get<String>("currency")?.let(Currency::of),
        saved["amount"] ?: "", saved.get<String>("direction")?.let(CashChangeDirection::valueOf) ?: CashChangeDirection.INCREASE,
        saved.get<String>("time")?.let(LocalDateTime::parse) ?: LocalDateTime.now(clock), saved["note"] ?: "",
        saved["revision"], saved["loaded"] ?: false, originalDeltaMinor = saved["originalDelta"] ?: 0))
    val state = mutable.asStateFlow()
    init {
        if (!state.value.loaded) viewModelScope.launch {
            try {
                val entry = repository.observe_entry(accountId, entryId).first() ?: throw DomainException(ErrorCode.NOT_FOUND)
                if (!entry.editable) throw DomainException(ErrorCode.SOURCE_RECORD)
                updateInternal(CashEntryDraft(entry.currency,
                    java.math.BigDecimal.valueOf(entry.delta_minor, entry.currency.fraction_digits).abs().toPlainString(),
                    if (entry.delta_minor < 0) CashChangeDirection.DECREASE else CashChangeDirection.INCREASE,
                    Instant.ofEpochMilli(entry.occurred_at_ms).atZone(clock.zone).toLocalDateTime(), entry.note, entry.revision,
                    true, originalDeltaMinor = entry.delta_minor))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(failed = true) }
        }
    }
    private fun updateInternal(input: CashEntryDraft) {
        mutable.value = input
        saved["currency"] = input.currency?.code
        saved["amount"] = input.amountInput
        saved["direction"] = input.direction.name
        saved["time"] = input.occurredAt.toString()
        saved["note"] = input.note
        saved["revision"] = input.revision
        saved["loaded"] = input.loaded
        saved["originalDelta"] = input.originalDeltaMinor
    }
    fun update(transform: (CashEntryDraft) -> CashEntryDraft) { if (submission.value.editable) updateInternal(transform(state.value)) }
    fun changePreview(): String? = runCatching {
        val input = state.value
        val currency = requireNotNull(input.currency)
        val amount = R.parse_minor(input.amountInput, currency)
        val effect = R.replace_contribution(0, input.originalDeltaMinor,
            if (input.direction == CashChangeDirection.DECREASE) -amount else amount)
        (if (effect > 0) "+" else "") + R.format_units(effect, currency.fraction_digits) + " " + currency.code
    }.getOrNull()
    fun submit() = session.submit {
        val input = state.value
        val amount = R.parse_minor(input.amountInput, input.currency ?: throw DomainException(ErrorCode.NOT_FOUND))
        EditCashEntry(operationId, entryId, input.revision ?: throw DomainException(ErrorCode.NOT_FOUND),
            if (input.direction == CashChangeDirection.DECREASE) -amount else amount,
            input.occurredAt.atZone(clock.zone).toInstant().toEpochMilli(), input.note)
    }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
