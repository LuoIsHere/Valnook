package dev.valnook.feature.cash

import java.time.YearMonth
import java.time.Clock
import dev.valnook.domain.repository.LedgerMonth
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.AssetSnapshot
import dev.valnook.domain.repository.CashRepository
import dev.valnook.domain.repository.LedgerCursor
import dev.valnook.domain.repository.PagedCashRepository
import dev.valnook.domain.repository.OverviewRepository
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
    data class Ready(val rows: List<CashAccount>, val allAccounts: List<CashAccount>,
        val creditSourceLabels: Map<Long, String> = emptyMap()) : CashBalancesState
}

sealed interface CashLedgerState {
    data object Loading : CashLedgerState
    data object Failed : CashLedgerState
    data class Ready(val account: CashAccount, val rows: List<CashEntry>, val hasMore: Boolean,
        val allAccounts: List<CashAccount> = emptyList(),
        val creditSourceLabels: Map<Long, String> = emptyMap(), val month: YearMonth = YearMonth.now()) : CashLedgerState
}

private data class BalanceContext(val accounts: List<CashAccount>, val sourceLabels: Map<Long, String>)

private fun AssetSnapshot.balanceContext(): BalanceContext {
    val parentNames = accounts.associate { it.id to it.name }
    return BalanceContext(cash, cash.associate { balance ->
        val parentName = parentNames[balance.account_id].orEmpty()
        balance.id to listOf(parentName, balance.name.ifBlank { balance.currency.code })
            .filter(String::isNotBlank).joinToString(" · ")
    })
}

class CashViewModel(
    private val accountId: Long,
    private val repository: CashRepository,
    private val pages: PagedCashRepository,
    private val saved: SavedStateHandle,
    private val overview: OverviewRepository? = null, private val clock: Clock = Clock.systemDefaultZone()
) : ViewModel() {
    private val balanceContext = overview?.observeSnapshot()?.map(AssetSnapshot::balanceContext)
        ?: flowOf(BalanceContext(emptyList(), emptyMap()))

    val balances = combine(repository.observe_cash(accountId), balanceContext) { rows, context ->
        CashBalancesState.Ready(rows, context.accounts.ifEmpty { rows }, context.sourceLabels) as CashBalancesState
    }.catch { emit(CashBalancesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashBalancesState.Loading)

    private val selected = saved.getStateFlow("selectedCashAccountId", 0L)
    val month = saved.getStateFlow("ledgerMonth", YearMonth.now(clock).toString())
    fun selectMonth(value: YearMonth) { saved["ledgerMonth"] = value.toString() }
    private var loadedMonth: String? = null
    private val requests = MutableStateFlow(0)
    private var loadedCashAccountId = 0L
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var rows = emptyList<CashEntry>()
    private var more = true

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries = combine(selected, month) { id, selectedMonth -> id to selectedMonth }.flatMapLatest { (cashAccountId, selectedMonth) ->
        val range = LedgerMonth(YearMonth.parse(selectedMonth), clock.zone)
        if (cashAccountId == 0L) flowOf<CashLedgerState>(CashLedgerState.Loading)
        else combine(repository.observeCashAccount(cashAccountId),
            pages.observeCashAccountRevision(cashAccountId), requests,
            balanceContext) { account, revision, request, context ->
            LedgerInput(account, revision, request, context)
        }.map<LedgerInput, CashLedgerState> { (account, revision, request, context) ->
            val current = account?.takeIf { it.account_id == accountId } ?: return@map CashLedgerState.Failed
            if (cashAccountId != loadedCashAccountId || revision != loadedRevision || loadedMonth != selectedMonth) {
                loadedMonth = selectedMonth
                rows = pages.cashAccountMonthPage(cashAccountId, range, null, PAGE_SIZE)
                loadedCashAccountId = cashAccountId
                loadedRevision = revision
                loadedRequest = request
                more = rows.size == PAGE_SIZE
            } else if (request != loadedRequest && more) {
                val last = rows.lastOrNull()
                val next = if (last == null) emptyList() else pages.cashAccountMonthPage(cashAccountId, range,
                    LedgerCursor(last.occurred_at_ms, last.id), PAGE_SIZE)
                rows = (rows + next).distinctBy { it.id }
                loadedRequest = request
                more = next.size == PAGE_SIZE
            }
            CashLedgerState.Ready(current, rows, more, context.accounts, context.sourceLabels, range.month)
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

    private data class LedgerInput(val account: CashAccount?, val revision: Long,
        val request: Int, val context: BalanceContext)
}
