package dev.valnook.feature.cash

import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.*

sealed interface CashBalancesState {
    data object Loading : CashBalancesState
    data object Failed : CashBalancesState
    data class Ready(val rows: List<CashBalance>) : CashBalancesState
}
sealed interface CashLedgerState {
    data object Loading : CashLedgerState
    data object Failed : CashLedgerState
    data class Ready(val rows: List<CashEntry>, val hasMore: Boolean) : CashLedgerState
}

class CashViewModel(private val accountId: Long, repository: CashRepository,
    private val pages: PagedCashRepository, private val saved: SavedStateHandle) : ViewModel() {
    val balances = repository.observe_cash(accountId).map<List<CashBalance>, CashBalancesState> { CashBalancesState.Ready(it) }
        .catch { emit(CashBalancesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashBalancesState.Loading)
    private val selected = saved.getStateFlow("selectedCurrency", "")
    private val requests = MutableStateFlow(0)
    private var loadedCurrency = ""
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var rows = emptyList<CashEntry>()
    private var more = true
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val entries = selected.flatMapLatest { code ->
        if (code.isEmpty()) flowOf<CashLedgerState>(CashLedgerState.Loading) else combine(pages.observeRevision(accountId, code), requests) { revision, request -> revision to request }
            .map<Pair<Long, Int>, CashLedgerState> { (revision, request) ->
                if (code != loadedCurrency || revision != loadedRevision) {
                    rows = pages.page(accountId, code, null, PAGE_SIZE)
                    loadedCurrency = code
                    loadedRevision = revision
                    loadedRequest = request
                    more = rows.size == PAGE_SIZE
                } else if (request != loadedRequest && more) {
                    val last = rows.lastOrNull()
                    val next = if (last == null) emptyList() else pages.page(accountId, code, LedgerCursor(last.occurred_at_ms, last.id), PAGE_SIZE)
                    rows = (rows + next).distinctBy { it.id }
                    loadedRequest = request
                    more = next.size == PAGE_SIZE
                }
                CashLedgerState.Ready(rows, more)
            }
    }.catch { emit(CashLedgerState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashLedgerState.Loading)
    fun watch_currency(code: String) { saved["selectedCurrency"] = Currency.of(code).code }
    fun load_more_entries() { requests.value++ }
    companion object { const val PAGE_SIZE = 50 }
}
