package dev.valnook.feature.deposits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.repository.DepositRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.LocalDate

sealed interface DepositDetailState {
    data object Loading:DepositDetailState
    data object Missing:DepositDetailState
    data object Failed:DepositDetailState
    data class Ready(val deposit:TermDeposit):DepositDetailState
}

class DepositDetailViewModel(account_id:Long,deposit_id:Long,repository:DepositRepository,private val clock:Clock):ViewModel() {
    private val attempts=MutableStateFlow(0)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state=attempts.flatMapLatest {
        repository.observe_deposit(account_id,deposit_id)
            .map<TermDeposit?,DepositDetailState>{if(it==null)DepositDetailState.Missing else DepositDetailState.Ready(it)}
            .onStart{emit(DepositDetailState.Loading)}.catch{emit(DepositDetailState.Failed)}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),DepositDetailState.Loading)
    private val _today=MutableStateFlow(LocalDate.now(clock).toEpochDay())
    val today=_today.asStateFlow()
    fun refresh_today(){_today.value=LocalDate.now(clock).toEpochDay()}
    fun retry(){attempts.value++}
}
