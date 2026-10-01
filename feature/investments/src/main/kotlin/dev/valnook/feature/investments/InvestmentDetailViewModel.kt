package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.*

data class PositionDetailState(val asset: Investment? = null, val trades: List<Trade> = emptyList(),
    val loaded: Boolean = false, val failed: Boolean = false, val hasMore: Boolean = false)

class InvestmentDetailViewModel(accountId: Long, private val positionId: Long,
    private val repository: InvestmentRepository) : ViewModel() {
    private val requests = MutableStateFlow(0)
    private var loadedRevision: Long? = null
    private var loadedRequest = 0
    private var history = emptyList<Trade>()
    private var hasMore = true
    private val pages = combine(repository.observe_trade_revision(positionId), requests) { revision, request -> revision to request }
        .map { (revision, request) ->
            if (revision != loadedRevision) {
                history = repository.trade_page(positionId, null)
                loadedRevision = revision
                loadedRequest = request
                hasMore = history.size == PAGE_SIZE
            } else if (request != loadedRequest && hasMore) {
                val last = history.lastOrNull()
                val next = if (last == null) emptyList() else
                    repository.trade_page(positionId, TradeCursor(last.occurred_at_ms, last.id))
                history = (history + next).distinctBy { it.id }
                loadedRequest = request
                hasMore = next.size == PAGE_SIZE
            }
            history to hasMore
        }
    val state = combine(repository.observe_investment(positionId), pages) { asset, (trades, more) ->
        PositionDetailState(asset?.takeIf { it.account_id == accountId }, trades, true, false, more)
    }.catch { emit(PositionDetailState(loaded = true, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), PositionDetailState())
    fun loadMore() { requests.value++ }
    companion object { const val PAGE_SIZE = 50 }
}
