package dev.valnook.feature.deposits

import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.LocalDate

sealed interface DepositsState {
    data object Loading : DepositsState
    data object Failed : DepositsState
    data class Ready(val rows: List<TermDeposit>, val hasMore: Boolean) : DepositsState
}

class DepositsViewModel(private val accountId: Long, private val closed: Boolean,
    private val repository: PagedDepositRepository, private val clock: Clock) : ViewModel() {
    private val requests = MutableStateFlow(0)
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var rows = emptyList<TermDeposit>()
    private var more = true
    val deposits = combine(repository.observeRevision(accountId), requests) { revision, request -> revision to request }
        .map<Pair<Long, Int>, DepositsState> { (revision, request) ->
            if (revision != loadedRevision) {
                rows = repository.page(accountId, closed, null, PAGE_SIZE)
                loadedRevision = revision
                loadedRequest = request
                more = rows.size == PAGE_SIZE
            } else if (request != loadedRequest && more) {
                val last = rows.lastOrNull()
                val next = if (last == null) emptyList() else repository.page(accountId, closed,
                    LedgerCursor(last.start_epoch_day, last.id), PAGE_SIZE)
                rows = (rows + next).distinctBy { it.id }
                loadedRequest = request
                more = next.size == PAGE_SIZE
            }
            DepositsState.Ready(rows, more)
        }.catch { emit(DepositsState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), DepositsState.Loading)
    private val mutableToday = MutableStateFlow(LocalDate.now(clock).toEpochDay())
    val today = mutableToday.asStateFlow()
    fun refresh_today() { mutableToday.value = LocalDate.now(clock).toEpochDay() }
    fun load_more() { requests.value++ }
    companion object { const val PAGE_SIZE = 50 }
}
