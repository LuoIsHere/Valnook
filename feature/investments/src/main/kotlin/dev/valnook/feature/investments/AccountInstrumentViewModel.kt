package dev.valnook.feature.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface AccountInstrumentState {
    data object Loading : AccountInstrumentState
    data object Failed : AccountInstrumentState
    data object Missing : AccountInstrumentState
    data class Ready(val instrument: Instrument, val position: Investment?) : AccountInstrumentState
}

/** Resolve the immutable account/instrument pair without creating a position on navigation. */
class AccountInstrumentViewModel(accountId: Long, instrumentId: Long, overview: OverviewRepository) : ViewModel() {
    val state = overview.observeSnapshot().map { snapshot ->
        val instrument = snapshot.instruments.firstOrNull { it.id == instrumentId }
        if (instrument == null || snapshot.accounts.none { it.id == accountId }) AccountInstrumentState.Missing
        else AccountInstrumentState.Ready(instrument,
            snapshot.positions.firstOrNull { it.account_id == accountId && it.instrumentId == instrumentId })
    }.flowOn(Dispatchers.Default).catch { emit(AccountInstrumentState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AccountInstrumentState.Loading)
}
