package dev.valnook.feature.accounts

import androidx.lifecycle.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface AccountsState {
    data object Loading : AccountsState
    data object Failed : AccountsState
    data class Ready(val snapshot: AssetSnapshot, val overview: AssetOverview) : AccountsState
}
class AccountsViewModel(repository: OverviewRepository, private val groups: AccountGroupWriter? = null) : ViewModel() {
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

    /** Saved at once, so the page reopens with the same groups folded. */
    fun toggleGroup(group: AccountGroup) {
        val writer = groups ?: return
        viewModelScope.launch {
            try { writer.setCollapsed(group.id, !group.collapsed) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { } // The header keeps its state; tapping again retries.
        }
    }
}
