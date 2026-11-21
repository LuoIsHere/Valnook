package dev.valnook.feature.cash

import java.time.YearMonth
import java.time.Clock
import dev.valnook.domain.repository.LedgerMonth
import dev.valnook.domain.repository.LedgerPeriod
import dev.valnook.domain.calculation.CreditBillingCalendar
import dev.valnook.domain.calculation.CreditCycle
import dev.valnook.domain.model.CreditCycleTotals
import java.time.LocalDate
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    /**
     * [cycle] is set while the changes are read by billing cycle, [currentCycle] whenever the sub-account has a
     * statement day (so it can be): the cycle today is in.
     */
    data class Ready(val account: CashAccount, val rows: List<CashEntry>, val hasMore: Boolean,
        val allAccounts: List<CashAccount> = emptyList(),
        val creditSourceLabels: Map<Long, String> = emptyMap(), val month: YearMonth = YearMonth.now(),
        val cycle: CreditCycle? = null, val currentCycle: CreditCycle? = null,
        val today: LocalDate = LocalDate.now()) : CashLedgerState
}

/** How a credit sub-account's changes are paged: by billing cycle (the default) or by calendar month. */
enum class CashLedgerView { CYCLE, MONTH }

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
    // Refresh time boundaries while visible, including a device time-zone change.
    @OptIn(ExperimentalCoroutinesApi::class)
    val trends = kotlinx.coroutines.flow.flow {
        while (true) { emit(clock.instant() to java.time.ZoneId.systemDefault()); kotlinx.coroutines.delay(60_000) }
    }.flatMapLatest { (now, zone) -> repository.observeTrends(accountId, now, zone) }
        .catch { emit(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyMap())

    private val balanceContext = overview?.observeSnapshot()?.map(AssetSnapshot::balanceContext)
        ?: flowOf(BalanceContext(emptyList(), emptyMap()))

    val balances = combine(repository.observe_cash(accountId), balanceContext) { rows, context ->
        CashBalancesState.Ready(rows, context.accounts.ifEmpty { rows }, context.sourceLabels) as CashBalancesState
    }.catch { emit(CashBalancesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashBalancesState.Loading)

    private val selected = saved.getStateFlow("selectedCashAccountId", 0L)
    val month = saved.getStateFlow("ledgerMonth", YearMonth.now(clock).toString())
    fun selectMonth(value: YearMonth) { saved["ledgerMonth"] = value.toString() }
    private val view = saved.getStateFlow<String?>("ledgerView", null)
    private val cycleMonth = saved.getStateFlow<String?>("ledgerCycle", null)
    fun selectView(value: CashLedgerView) { saved["ledgerView"] = value.name }
    /** Shows the cycle whose statement falls in [statementMonth], switching to cycles if months were shown. */
    fun selectCycle(statementMonth: YearMonth) {
        saved["ledgerView"] = CashLedgerView.CYCLE.name
        saved["ledgerCycle"] = statementMonth.toString()
    }
    /** Today, again each minute, so the current cycle moves on at midnight. */
    private val days = kotlinx.coroutines.flow.flow {
        while (true) { emit(LocalDate.now(clock)); kotlinx.coroutines.delay(60_000) }
    }.distinctUntilChanged()
    private var loadedPeriod: LedgerPeriod? = null
    private val requests = MutableStateFlow(0)
    private var loadedCashAccountId = 0L
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var rows = emptyList<CashEntry>()
    private var more = true

    @OptIn(ExperimentalCoroutinesApi::class)
    val entries = combine(selected, month, view, cycleMonth) { id, selectedMonth, selectedView, selectedCycle ->
        LedgerChoice(id, selectedMonth, selectedView, selectedCycle)
    }.flatMapLatest { (cashAccountId, selectedMonth, selectedView, selectedCycle) ->
        val range = LedgerMonth(YearMonth.parse(selectedMonth), clock.zone)
        if (cashAccountId == 0L) flowOf<CashLedgerState>(CashLedgerState.Loading)
        else combine(repository.observeCashAccount(cashAccountId),
            pages.observeCashAccountRevision(cashAccountId), requests,
            balanceContext, days) { account, revision, request, context, today ->
            LedgerInput(account, revision, request, context, today)
        }.map<LedgerInput, CashLedgerState> { (account, revision, request, context, today) ->
            val current = account?.takeIf { it.account_id == accountId } ?: return@map CashLedgerState.Failed
            val profile = current.creditProfile
            val currentCycle = profile?.let { runCatching { CreditBillingCalendar.currentCycle(today, it) }.getOrNull() }
            val cycle = if (profile == null || currentCycle == null || selectedView == CashLedgerView.MONTH.name) null
                else selectedCycle?.let { runCatching { CreditBillingCalendar.cycle(YearMonth.parse(it), profile) }.getOrNull() }
                    ?: currentCycle
            val period = cycle?.period(clock.zone) ?: range.period
            if (cashAccountId != loadedCashAccountId || revision != loadedRevision || loadedPeriod != period) {
                loadedPeriod = period
                rows = pages.cashAccountPeriodPage(cashAccountId, period, null, PAGE_SIZE)
                loadedCashAccountId = cashAccountId
                loadedRevision = revision
                loadedRequest = request
                more = rows.size == PAGE_SIZE
            } else if (request != loadedRequest && more) {
                val last = rows.lastOrNull()
                val next = if (last == null) emptyList() else pages.cashAccountPeriodPage(cashAccountId, period,
                    LedgerCursor(last.occurred_at_ms, last.id), PAGE_SIZE)
                rows = (rows + next).distinctBy { it.id }
                loadedRequest = request
                more = next.size == PAGE_SIZE
            }
            CashLedgerState.Ready(current, rows, more, context.accounts, context.sourceLabels, range.month,
                cycle, currentCycle, today)
        }
    }.catch { emit(CashLedgerState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), CashLedgerState.Loading)

    /** The shown cycle's spending, refunds and repayments, again whenever a bill on it changes; null for months. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val shownCycleTotals = entries.map { state ->
        (state as? CashLedgerState.Ready)?.let { ready -> ready.cycle?.let { ready.account.id to it } }
    }.distinctUntilChanged().flatMapLatest { key ->
        if (key == null) flowOf(null)
        else pages.observeCycleTotals(key.first, key.second.period(clock.zone)).map<CreditCycleTotals, CreditCycleTotals?> { it }
    }.catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    /** The credit sub-account's cycle so far and the statement before it; null for a savings account. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val cycleSummary = selected.flatMapLatest { cashAccountId ->
        if (cashAccountId == 0L) flowOf(null)
        else combine(repository.observeCashAccount(cashAccountId).map { it?.creditProfile }.distinctUntilChanged(), days) { profile, today ->
            profile to today
        }.distinctUntilChanged().flatMapLatest { (profile, today) ->
            if (profile == null) flowOf(null)
            else pages.observeCreditCycleSummary(cashAccountId, profile, today, clock.zone)
        }
    }.catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), null)

    fun watchCashAccount(cashAccountId: Long) {
        // Another sub-account starts from its own default, not the cycle picked on the last one.
        if (saved.get<Long>("selectedCashAccountId") != cashAccountId) {
            saved["ledgerView"] = null
            saved["ledgerCycle"] = null
        }
        saved["selectedCashAccountId"] = cashAccountId
    }

    fun load_more_entries() {
        requests.value++
    }

    companion object {
        const val PAGE_SIZE = 50
    }

    private data class LedgerInput(val account: CashAccount?, val revision: Long,
        val request: Int, val context: BalanceContext, val today: LocalDate)

    private data class LedgerChoice(val cashAccountId: Long, val month: String, val view: String?, val cycle: String?)
}
