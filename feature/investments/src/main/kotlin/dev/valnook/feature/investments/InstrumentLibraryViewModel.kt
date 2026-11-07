package dev.valnook.feature.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.AssetType
import dev.valnook.domain.model.InstrumentAssets
import dev.valnook.domain.repository.InvestmentRepository
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface InstrumentLibraryState {
    data object Loading : InstrumentLibraryState
    data object Failed : InstrumentLibraryState
    /** [soldOut]: instruments some account has traded but none holds any longer. */
    data class Ready(val instruments: List<InstrumentAssets>, val types: List<AssetType>,
        val soldOut: Set<Long> = emptySet()) : InstrumentLibraryState
}

class InstrumentLibraryViewModel(overview: OverviewRepository, investments: InvestmentRepository) : ViewModel() {
    val state = combine(overview.observeSnapshot(), investments.observe_types()) { snapshot, types ->
        val soldOut = snapshot.positions.groupBy { it.instrumentId }
            .filterValues { positions -> positions.none { it.holding_quantity_e8 > 0 } }.keys
        InstrumentLibraryState.Ready(AssetValuation.instrumentSummaries(snapshot), types, soldOut) as InstrumentLibraryState
    }.flowOn(Dispatchers.Default).catch { emit(InstrumentLibraryState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), InstrumentLibraryState.Loading)
}
