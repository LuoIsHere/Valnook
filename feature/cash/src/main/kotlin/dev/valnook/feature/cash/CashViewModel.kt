package dev.valnook.feature.cash

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.repository.CashRepository
import dev.valnook.domain.repository.LedgerCursor
import dev.valnook.domain.repository.PagedCashRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface CashBalancesState {
    data object Loading : CashBalancesState
    data object Failed : CashBalancesState
    data class Ready(val rows: List<CashAccount>) : CashBalancesState
}

sealed interface CashLedgerState {
    data object Loading : CashLedgerState
    data object Failed : CashLedgerState
    data class Ready(val account: CashAccount, val rows: List<CashEntry>, val hasMore: Boolean) : CashLedgerState
}

class CashViewModel(
    private val accountId: Long,
    private val repository: CashRepository,
    private val pages: PagedCashRepository,
    private val saved: SavedStateHandle
) : ViewModel() {
    val balances = repository.observe_cash(accountId).map<List<CashAccount>, CashBalancesState> {
        CashBalancesState.Ready(it)
    }.catch { emit(CashBalancesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashBalancesState.Loading)

    private val selected = saved.getStateFlow("selectedCashAccountId", 0L)
    private val requests = MutableStateFlow(0)
    private var loadedCashAccountId = 0L
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var rows = emptyList<CashEntry>()
    private var more = true

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries = selected.flatMapLatest { cashAccountId ->
        if (cashAccountId == 0L) flowOf<CashLedgerState>(CashLedgerState.Loading)
        else combine(repository.observeCashAccount(cashAccountId),
            pages.observeCashAccountRevision(cashAccountId), requests) { account, revision, request ->
            Triple(account, revision, request)
        }.map<Triple<CashAccount?, Long, Int>, CashLedgerState> { (account, revision, request) ->
            val current = account?.takeIf { it.account_id == accountId } ?: return@map CashLedgerState.Failed
            if (cashAccountId != loadedCashAccountId || revision != loadedRevision) {
                rows = pages.cashAccountPage(cashAccountId, null, PAGE_SIZE)
                loadedCashAccountId = cashAccountId
                loadedRevision = revision
                loadedRequest = request
                more = rows.size == PAGE_SIZE
            } else if (request != loadedRequest && more) {
                val last = rows.lastOrNull()
                val next = if (last == null) emptyList() else pages.cashAccountPage(cashAccountId,
                    LedgerCursor(last.occurred_at_ms, last.id), PAGE_SIZE)
                rows = (rows + next).distinctBy { it.id }
                loadedRequest = request
                more = next.size == PAGE_SIZE
            }
            CashLedgerState.Ready(current, rows, more)
        }
    }.catch { emit(CashLedgerState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashLedgerState.Loading)

    fun watchCashAccount(cashAccountId: Long) {
        saved["selectedCashAccountId"] = cashAccountId
    }

    fun load_more_entries() {
        requests.value++
    }

    companion object {
        const val PAGE_SIZE = 50
    }
}
