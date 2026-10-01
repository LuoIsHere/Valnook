package dev.valnook.feature.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.InstrumentAssets
import dev.valnook.domain.repository.InvestmentRepository
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface InstrumentLibraryState {
    data object Loading : InstrumentLibraryState
    data object Failed : InstrumentLibraryState
    data class Ready(val instruments: List<InstrumentAssets>, val hasTypes: Boolean) : InstrumentLibraryState
}

class InstrumentLibraryViewModel(overview: OverviewRepository, investments: InvestmentRepository) : ViewModel() {
    val state = combine(overview.observeSnapshot(), investments.observe_types()) { snapshot, types ->
        InstrumentLibraryState.Ready(AssetValuation.instrumentSummaries(snapshot), types.isNotEmpty()) as InstrumentLibraryState
    }.flowOn(Dispatchers.Default).catch { emit(InstrumentLibraryState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), InstrumentLibraryState.Loading)
}
