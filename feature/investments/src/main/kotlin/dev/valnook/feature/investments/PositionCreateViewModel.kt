package dev.valnook.feature.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.command.SubmissionSession
import dev.valnook.domain.model.Instrument
import dev.valnook.domain.model.InvestmentSection
import dev.valnook.domain.repository.CreateInvestmentPosition
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.InstrumentRepository
import dev.valnook.domain.repository.InvestmentRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

class PositionCreateViewModel(
    private val accountId: Long,
    private val initialInstrumentId: Long?,
    instruments: InstrumentRepository,
    investments: InvestmentRepository,
    commands: FinancialCommands,
    private val saved: SavedStateHandle
) : ViewModel() {
    private val operationId = saved.get<String>("operationId")
        ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) {
        saved["submission"] = it.name
    }
    val submission = session.state
    private val mutableSelectedInstrumentId = MutableStateFlow(
        saved.get<Long>("instrumentId") ?: initialInstrumentId
    )
    val selectedInstrumentId = mutableSelectedInstrumentId.asStateFlow()

    val available = combine(instruments.observeInstruments(),
        investments.observe_investments(accountId, 10_000, InvestmentSection.ALL)) { catalog, positions ->
        val existing = positions.mapTo(mutableSetOf()) { it.instrumentId }
        catalog.filterNot { it.id in existing }
    }.catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())

    fun select(instrument: Instrument) {
        if (submission.value.editable) {
            mutableSelectedInstrumentId.value = instrument.id
            saved["instrumentId"] = instrument.id
        }
    }

    fun submit() = session.submit {
        CreateInvestmentPosition(operationId, accountId, requireNotNull(selectedInstrumentId.value))
    }

    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
