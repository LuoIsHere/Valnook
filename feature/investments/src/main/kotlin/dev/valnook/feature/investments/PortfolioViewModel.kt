package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface PortfolioState {
    data object Loading : PortfolioState
    data object Failed : PortfolioState
    data class Ready(val snapshot: AssetSnapshot, val overview: AssetOverview,
        val instrumentSummaries: List<InstrumentAssets>) : PortfolioState
}
class PortfolioViewModel(repository: OverviewRepository) : ViewModel() {
    val state = repository.observeSnapshot().map<AssetSnapshot, PortfolioState> {
        PortfolioState.Ready(it, AssetValuation.calculate(it), AssetValuation.instrumentSummaries(it))
    }.flowOn(Dispatchers.Default).catch { emit(PortfolioState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PortfolioState.Loading)
}
