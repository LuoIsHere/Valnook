package dev.valnook.feature.investments

import java.time.YearMonth
import java.time.Clock
import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.*

data class PositionDetailState(val asset: Investment? = null, val trades: List<Trade> = emptyList(),
    val loaded: Boolean = false, val failed: Boolean = false, val hasMore: Boolean = false, val month: YearMonth = YearMonth.now())

class InvestmentDetailViewModel(accountId: Long, private val positionId: Long,
    private val repository: InvestmentRepository, private val saved: SavedStateHandle = SavedStateHandle(),
    private val clock: Clock = Clock.systemDefaultZone()) : ViewModel() {
    val month = saved.getStateFlow("ledgerMonth", YearMonth.now(clock).toString())
    fun selectMonth(value: YearMonth) { saved["ledgerMonth"] = value.toString() }
    private var loadedMonth: String? = null
    private val requests = MutableStateFlow(0)
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var history = emptyList<Trade>()
    private var hasMore = true
    private val pages = combine(repository.observe_trade_revision(positionId), requests, month) { revision, request, month -> Triple(revision, request, month) }
        .map { (revision, request, month) ->
            val range = LedgerMonth(YearMonth.parse(month), clock.zone)
            if (revision != loadedRevision || loadedMonth != month) {
                loadedMonth = month
                history = repository.tradeMonthPage(positionId, range, null, PAGE_SIZE)
                loadedRevision = revision
                loadedRequest = request
                hasMore = history.size == PAGE_SIZE
            } else if (request != loadedRequest && hasMore) {
                val last = history.lastOrNull()
                val next = if (last == null) emptyList() else
                    repository.tradeMonthPage(positionId, range, TradeCursor(last.occurred_at_ms, last.id), PAGE_SIZE)
                history = (history + next).distinctBy { it.id }
                loadedRequest = request
                hasMore = next.size == PAGE_SIZE
            }
            Triple(history, hasMore, range.month)
        }
    val state = combine(repository.observe_investment(positionId), pages) { asset, (trades, more, month) ->
        PositionDetailState(asset?.takeIf { it.account_id == accountId }, trades, true, false, more, month)
    }.catch { emit(PositionDetailState(loaded = true, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PositionDetailState())
    fun loadMore() { requests.value++ }
    companion object { const val PAGE_SIZE = 50 }
}
