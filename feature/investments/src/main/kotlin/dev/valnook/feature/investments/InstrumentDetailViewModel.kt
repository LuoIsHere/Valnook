package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.command.SubmissionSession
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.YearMonth

/** One account's position in the instrument; a closed one still counts for what it realized. */
data class InstrumentHolding(val account: SavingsAccount, val position: Investment, val profit: InvestmentProfit,
    val marketValue: BigDecimal)

/** Sums over every account. One instrument has one currency, so the amounts add up as they are. */
data class InstrumentTotals(val quantityE8: Long, val marketValue: BigDecimal, val cost: BigDecimal?,
    val average: BigDecimal?, val realized: BigDecimal?, val unrealized: BigDecimal?, val unrealizedPercent: BigDecimal?,
    val dividends: BigDecimal = BigDecimal.ZERO)

sealed interface InstrumentDetailState {
    data object Loading : InstrumentDetailState
    data object Failed : InstrumentDetailState
    data object Missing : InstrumentDetailState
    /** [accounts] are every account, in their order, for choosing where to buy. */
    data class Ready(val instrument: Instrument, val holdings: List<InstrumentHolding>, val accounts: List<SavingsAccount>,
        val totals: InstrumentTotals, val prices: List<InstrumentPricePoint>) : InstrumentDetailState
}

/** The price dialog's input and its one request. */
class PriceEditor(val operationId: String, val instrument: Instrument, val session: SubmissionSession) {
    val input = MutableStateFlow(priceText(instrument.currentPriceE5))
    fun update(value: String) { if (session.state.value.editable) input.value = value }
}

data class InstrumentTradesState(val rows: List<Trade> = emptyList(), val hasMore: Boolean = false,
    val loaded: Boolean = false, val failed: Boolean = false)

/**
 * The one page of an instrument, whichever way it was opened: its totals over every account, each account's share,
 * its recent prices and its trades a month at a time, all accounts or one.
 */
class InstrumentDetailViewModel(val instrumentId: Long, overview: OverviewRepository,
    private val investments: InvestmentRepository, instruments: InstrumentRepository,
    private val saved: SavedStateHandle = SavedStateHandle(), private val clock: Clock = Clock.systemDefaultZone(),
    private val commands: FinancialCommands? = null) : ViewModel() {
    val month = saved.getStateFlow("ledgerMonth", YearMonth.now(clock).toString())
    /** The account whose trades are shown, or null for all of them. */
    val accountFilter = saved.getStateFlow<Long?>("accountFilter", null)
    fun selectMonth(value: YearMonth) { saved["ledgerMonth"] = value.toString() }
    fun selectAccount(id: Long?) { saved["accountFilter"] = id }

    private val snapshots = overview.observeSnapshot()

    val state: StateFlow<InstrumentDetailState> = combine(snapshots,
        instruments.observePriceHistory(instrumentId).catch { emit(emptyList()) },
        investments.observeInstrumentDividends(instrumentId).catch { emit(0L) }) { snapshot, prices, dividends ->
        build(snapshot, prices, dividends)
    }.flowOn(Dispatchers.Default).catch { emit(InstrumentDetailState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), InstrumentDetailState.Loading)

    private fun build(snapshot: AssetSnapshot, prices: List<InstrumentPricePoint>, dividendsMinor: Long): InstrumentDetailState {
        val instrument = snapshot.instruments.firstOrNull { it.id == instrumentId } ?: return InstrumentDetailState.Missing
        val order = snapshot.accounts.withIndex().associate { it.value.id to it.index }
        val accounts = snapshot.accounts.associateBy { it.id }
        val holdings = snapshot.positions.filter { it.instrumentId == instrumentId && (it.holding_quantity_e8 > 0 || it.isClosed) }
            .mapNotNull { position -> accounts[position.account_id]?.let { account ->
                InstrumentHolding(account, position, InvestmentProfitCalculator.fromReadModel(position),
                    if (position.holding_quantity_e8 > 0) AssetValuation.marketValue(position) else BigDecimal.ZERO)
            } }
            // Held first, largest first; closed ones after, in the accounts' order.
            .sortedWith(compareByDescending<InstrumentHolding> { it.position.holding_quantity_e8 > 0 }
                .thenByDescending { it.marketValue }.thenBy { order[it.account.id] ?: Int.MAX_VALUE })
        return InstrumentDetailState.Ready(instrument, holdings, snapshot.accounts,
            totals(holdings).copy(dividends = BigDecimal.valueOf(dividendsMinor, instrument.currency.fraction_digits)), prices)
    }

    private fun totals(holdings: List<InstrumentHolding>): InstrumentTotals {
        val held = holdings.filter { it.position.holding_quantity_e8 > 0 }
        fun sum(values: List<BigDecimal?>): BigDecimal? =
            if (values.any { it == null }) null else values.fold(BigDecimal.ZERO) { total, value -> total + value!! }
        val quantity = held.fold(0L) { total, it -> Math.addExact(total, it.position.holding_quantity_e8) }
        val cost = sum(held.map { it.profit.remainingCost })
        val unrealized = sum(held.map { it.profit.unrealized })
        return InstrumentTotals(quantity, held.fold(BigDecimal.ZERO) { total, it -> total + it.marketValue }, cost,
            cost?.takeIf { quantity > 0 }?.divide(BigDecimal.valueOf(quantity, 8), 10, RoundingMode.HALF_UP),
            sum(holdings.map { it.profit.realized }), unrealized,
            if (cost != null && unrealized != null && cost.signum() > 0)
                unrealized.multiply(BigDecimal(100)).divide(cost, 6, RoundingMode.HALF_UP) else null)
    }

    /** The filtered account's position; [NO_POSITION] when it has none, so nothing is shown. */
    private val source = combine(accountFilter, snapshots) { filter, snapshot ->
        filter?.let { id -> snapshot.positions.firstOrNull { it.instrumentId == instrumentId && it.account_id == id }?.id ?: NO_POSITION }
    }.distinctUntilChanged()

    private val requests = MutableStateFlow(0)
    private var loadedKey: Triple<Long?, String, String>? = null
    private var loadedRequest = 0
    private var history = emptyList<Trade>()
    private var hasMore = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val trades: StateFlow<InstrumentTradesState> = combine(source.flatMapLatest { position ->
        when (position) {
            null -> investments.observeInstrumentTradeRevision(instrumentId)
            NO_POSITION -> flowOf("")
            else -> investments.observe_trade_revision(position).map { it.toString() }
        }.map { position to it }
    }, requests, month) { (position, revision), request, month ->
        val range = LedgerMonth(YearMonth.parse(month), clock.zone)
        suspend fun page(cursor: TradeCursor?): List<Trade> = when (position) {
            null -> investments.instrumentTradeMonthPage(instrumentId, range, cursor, PAGE_SIZE)
            NO_POSITION -> emptyList()
            else -> investments.tradeMonthPage(position, range, cursor, PAGE_SIZE)
        }
        val key = Triple(position, revision, month)
        if (key != loadedKey) {
            history = page(null)
            loadedKey = key
            loadedRequest = request
            hasMore = history.size == PAGE_SIZE
        } else if (request != loadedRequest && hasMore) {
            val next = history.lastOrNull()?.let { page(TradeCursor(it.occurred_at_ms, it.id)) }.orEmpty()
            history = (history + next).distinctBy { it.id }
            loadedRequest = request
            hasMore = next.size == PAGE_SIZE
        }
        InstrumentTradesState(history, hasMore, true)
    }.catch { emit(InstrumentTradesState(loaded = true, failed = true)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), InstrumentTradesState())

    fun loadMore() { requests.value++ }

    /** Open while the price dialog is shown; each opening is a new request. */
    val priceEditor = MutableStateFlow<PriceEditor?>(null)

    fun openPriceEditor() {
        val ready = state.value as? InstrumentDetailState.Ready ?: return
        val commands = commands ?: return
        priceEditor.value = PriceEditor(java.util.UUID.randomUUID().toString(), ready.instrument,
            SubmissionSession(commands, viewModelScope))
    }

    /** Kept open while saving, so an answer is not lost. */
    fun closePriceEditor() {
        if (priceEditor.value?.session?.state?.value?.phase != SubmissionPhase.WORKING) priceEditor.value = null
    }

    fun savePrice() {
        val editor = priceEditor.value ?: return
        editor.session.submit {
            // Saved by hand, so the same price still counts as today's update and is drawn.
            UpdateInstrumentPrices(editor.operationId, listOf(InstrumentPriceChange(editor.instrument.id,
                editor.instrument.revision, DecimalRules.parse_units(editor.input.value.trim(), 5))), recordUnchanged = true)
        }
    }

    companion object {
        const val PAGE_SIZE = 50
        private const val NO_POSITION = -1L
    }
}
