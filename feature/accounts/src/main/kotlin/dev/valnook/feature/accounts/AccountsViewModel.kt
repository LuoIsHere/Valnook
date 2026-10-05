package dev.valnook.feature.accounts

import androidx.lifecycle.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*

sealed interface AccountsState {
    data object Loading : AccountsState
    data object Failed : AccountsState
    data class Ready(val snapshot: AssetSnapshot, val overview: AssetOverview) : AccountsState
}
class AccountsViewModel(repository: OverviewRepository) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private var startedAttempt = -1
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state = refresh.flatMapLatest { attempt -> repository.observeSnapshot().map<AssetSnapshot, AccountsState> {
        AccountsState.Ready(it, AssetValuation.calculate(it))
    }.flowOn(Dispatchers.Default).onStart {
        // Returning to a retained root must not replace its list with a loading subtree.
        if (startedAttempt != attempt) { startedAttempt = attempt; emit(AccountsState.Loading) }
    }.catch { emit(AccountsState.Failed) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AccountsState.Loading)
    fun reload() { refresh.update { it + 1 } }
}
