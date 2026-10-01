package dev.valnook.feature.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.repository.CashRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

sealed interface CashEntryState {
    data object Loading:CashEntryState
    data object Missing:CashEntryState
    data object Failed:CashEntryState
    data class Ready(val entry:CashEntry):CashEntryState
}

class CashEntryViewModel(cashAccountId:Long,entryId:Long,repository:CashRepository):ViewModel() {
    private val attempts=MutableStateFlow(0)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state=attempts.flatMapLatest {
        repository.observeCashEntry(cashAccountId,entryId)
            .map<CashEntry?,CashEntryState>{entry->if(entry==null)CashEntryState.Missing else CashEntryState.Ready(entry)}
            .onStart {emit(CashEntryState.Loading)}
            .catch {emit(CashEntryState.Failed)}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),CashEntryState.Loading)
    fun retry(){attempts.value++}
}
