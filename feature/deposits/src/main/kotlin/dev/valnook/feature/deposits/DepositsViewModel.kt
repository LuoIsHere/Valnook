package dev.valnook.feature.deposits

import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

sealed interface DepositsState {
    data object Loading : DepositsState
    data object Failed : DepositsState
    data class Ready(val rows: List<TermDeposit>, val hasMore: Boolean, val month: YearMonth? = null) : DepositsState
}

class DepositsViewModel(private val accountId: Long, private val closed: Boolean,
    private val repository: PagedDepositRepository, private val clock: Clock,
    private val saved: SavedStateHandle = SavedStateHandle()) : ViewModel() {
    val month = saved.getStateFlow("ledgerMonth", YearMonth.now(clock).toString())
    fun selectMonth(value: YearMonth) { saved["ledgerMonth"] = value.toString() }
    private var loadedMonth: String? = null
    private val requests = MutableStateFlow(0)
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var rows = emptyList<TermDeposit>()
    private var more = true
    val deposits = combine(repository.observeRevision(accountId), requests, month) { revision, request, month -> Triple(revision, request, month) }
        .map<Triple<Long, Int, String>, DepositsState> { (revision, request, month) ->
            if (revision != loadedRevision || (closed && loadedMonth != month)) {
                loadedMonth = month
                rows = loadPage(month, null)
                loadedRevision = revision
                loadedRequest = request
                more = rows.size == PAGE_SIZE
            } else if (request != loadedRequest && more) {
                val last = rows.lastOrNull()
                val next = if (last == null) emptyList() else loadPage(month, LedgerCursor(if (closed) last.closedAtMs ?: 0 else last.start_epoch_day, last.id))
                rows = (rows + next).distinctBy { it.id }
                loadedRequest = request
                more = next.size == PAGE_SIZE
            }
            DepositsState.Ready(rows, more, if (closed) YearMonth.parse(month) else null)
        }.catch { emit(DepositsState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), DepositsState.Loading)
    private suspend fun loadPage(month: String, cursor: LedgerCursor?) = if (closed)
        repository.monthPage(accountId, LedgerMonth(YearMonth.parse(month), clock.zone), cursor, PAGE_SIZE)
        else repository.page(accountId, false, cursor, PAGE_SIZE)
    private val mutableToday = MutableStateFlow(LocalDate.now(clock).toEpochDay())
    val today = mutableToday.asStateFlow()
    fun refresh_today() { mutableToday.value = LocalDate.now(clock).toEpochDay() }
    fun load_more() { requests.value++ }
    companion object { const val PAGE_SIZE = 50 }
}
