package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

data class InstrumentEditUiState(val name: String = "", val symbol: String = "", val typeId: Long? = null,
    val currency: Currency = Currency.of("CNY"), val priceInput: String = "", val expectedRevision: Long? = null,
    val currencyLocked: Boolean = false, val currencyPriceConfirmed: Boolean = false, val loaded: Boolean = false,
    val loadFailed: Boolean = false)
class InstrumentEditViewModel(private val instrumentId: Long?, private val instruments: InstrumentRepository,
    investments: InvestmentRepository, commands: FinancialCommands, private val saved: SavedStateHandle) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    val types = investments.observe_types().map<List<AssetType>, AssetTypesState> { AssetTypesState.Ready(it) }
        .catch { emit(AssetTypesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AssetTypesState.Loading)
    private val mutable = MutableStateFlow(InstrumentEditUiState(saved["name"] ?: "", saved["symbol"] ?: "",
        saved["typeId"], Currency.of(saved["currency"] ?: "CNY"), saved["price"] ?: "", saved["revision"],
        saved["locked"] ?: false, saved["confirmed"] ?: false, saved["loaded"] ?: false))
    val state = mutable.asStateFlow()
    init {
        if (!state.value.loaded) viewModelScope.launch {
            try {
                val instrument = instrumentId?.let { instruments.observeInstrument(it).first() ?: throw DomainException(ErrorCode.NOT_FOUND) }
                updateInternal(if (instrument == null) state.value.copy(loaded = true) else InstrumentEditUiState(instrument.name,
                    instrument.symbol, instrument.typeId, instrument.currency,
                    java.math.BigDecimal.valueOf(instrument.currentPriceE5, 5).stripTrailingZeros().toPlainString(),
                    instrument.revision, instrument.currencyLocked, false, true))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(loadFailed = true) }
        }
    }
    private fun updateInternal(value: InstrumentEditUiState) {
        mutable.value = value
        saved["name"] = value.name
        saved["symbol"] = value.symbol
        saved["typeId"] = value.typeId
        saved["currency"] = value.currency.code
        saved["price"] = value.priceInput
        saved["revision"] = value.expectedRevision
        saved["locked"] = value.currencyLocked
        saved["confirmed"] = value.currencyPriceConfirmed
        saved["loaded"] = value.loaded
    }
    fun update(transform: (InstrumentEditUiState) -> InstrumentEditUiState) {
        if (submission.value.editable) updateInternal(transform(state.value))
    }
    fun submit() = session.submit {
        val input = state.value
        SaveInstrument(operationId, instrumentId, input.expectedRevision, input.name, input.symbol,
            input.typeId ?: throw DomainException(ErrorCode.NOT_FOUND), input.currency.code,
            R.parse_units(input.priceInput, 5), input.currencyPriceConfirmed)
    }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
