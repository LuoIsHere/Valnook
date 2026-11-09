package dev.valnook.feature.investments

import androidx.lifecycle.*
import dev.valnook.domain.command.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.util.UUID

enum class TradeFormMode { CREATE, EDIT, DELETE }
data class TradeFormUiState(val mode: TradeFormMode, val instrumentId: Long?, val positionId: Long?,
    val tradeId: Long?, val expectedRevision: Long?, val currency: Currency?,
    val direction: Direction, val quantityInput: String, val executionPriceInput: String, val feeInput: String,
    val occurredAt: LocalDateTime, val cashLinked: Boolean, val cashAccountId: Long? = null, val name: String = "",
    val loaded: Boolean = false, val failed: Boolean = false,
    val originalCashImpactMinor: Long = 0, val setAsCurrentPrice: Boolean = false, val priceUpdatedAtMs: Long? = null,
    val originalOccurredAtMs: Long? = null)

/**
 * The time to store: the record's own while the local time shown is unchanged. When clocks fall back, one local
 * time is two instants, and converting it again would move the record an hour earlier.
 */
internal fun keptEpoch(local: LocalDateTime, zone: ZoneId, original: Long?): Long =
    original?.takeIf { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() == local }
        ?: local.atZone(zone).toInstant().toEpochMilli()

internal fun tradeAmountWithFee(direction: Direction, amountMinor: Long, feeMinor: Long): Long = when (direction) {
    Direction.BUY -> R.add(amountMinor, feeMinor)
    Direction.SELL -> R.replace_contribution(amountMinor, feeMinor, 0)
    // Dividends carry no fee: what arrived is the amount.
    Direction.DIVIDEND -> amountMinor
}

class TradeFormViewModel(initialAccountId: Long, mode: TradeFormMode, instrumentId: Long?,
    positionId: Long?, tradeId: Long?, direction: Direction, private val repository: InvestmentRepository,
    private val instruments: InstrumentRepository, cashRepository: CashRepository, commands: FinancialCommands,
    private val clock: Clock, private val saved: SavedStateHandle) : ViewModel() {
    private val operationId = saved.get<String>("operationId") ?: UUID.randomUUID().toString().also { saved["operationId"] = it }
    private val session = SubmissionSession(commands, viewModelScope,
        saved.get<String>("submission")?.let(SubmissionPhase::valueOf) ?: SubmissionPhase.IDLE) { saved["submission"] = it.name }
    val submission = session.state
    private val mutable = MutableStateFlow(TradeFormUiState(mode, saved["instrumentId"] ?: instrumentId,
        saved["positionId"] ?: positionId, tradeId,
        saved["revision"], saved.get<String>("currency")?.let(Currency::of),
        saved.get<String>("direction")?.let(Direction::valueOf) ?: direction,
        saved["quantity"] ?: "", saved["price"] ?: "", saved["fee"] ?: "0",
        saved.get<String>("occurredAt")?.let(LocalDateTime::parse) ?: LocalDateTime.now(clock).withSecond(0).withNano(0),
        saved["linked"] ?: false, saved["cashAccountId"], saved["name"] ?: "",
        saved["loaded"] ?: false, originalCashImpactMinor = saved["originalCashImpact"] ?: 0,
        setAsCurrentPrice = saved["setAsCurrentPrice"] ?: false, priceUpdatedAtMs = saved["priceUpdatedAt"],
        originalOccurredAtMs = saved["originalOccurredAt"]))
    val state = mutable.asStateFlow()
    private val account = MutableStateFlow(saved.get<Long>("accountId") ?: initialAccountId)
    /** The account it is recorded in; a new trade from an instrument's page may move to another before saving. */
    val accountId: StateFlow<Long> = account.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val cashAccounts = account.flatMapLatest { id -> cashRepository.observe_cash(id) }.map { rows ->
        rows.filter { it.type == BalanceAccountType.SAVINGS }
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    init { if (!state.value.loaded) load() }

    private fun load() = viewModelScope.launch {
        try {
            val input = state.value
            val trade = input.tradeId?.let { repository.get_trade(it) ?: throw DomainException(ErrorCode.NOT_FOUND) }
            val assetId = trade?.investment_id ?: input.positionId
            val asset = assetId?.let { repository.observe_investment(it).first() ?: throw DomainException(ErrorCode.NOT_FOUND) }
            if (asset != null && asset.account_id != account.value) throw DomainException(ErrorCode.NOT_FOUND)
            val instrument = (asset?.instrumentId ?: input.instrumentId)?.let {
                instruments.observeInstrument(it).first() ?: throw DomainException(ErrorCode.NOT_FOUND)
            }
            change(input.copy(instrumentId = instrument?.id, positionId = asset?.id,
                expectedRevision = trade?.revision ?: asset?.revision, currency = instrument?.currency,
                direction = trade?.direction ?: input.direction,
                quantityInput = trade?.let { R.format_e8(it.quantity_e8) } ?: input.quantityInput,
                executionPriceInput = trade?.let { R.format_e8(it.execution_price_e8) }
                    ?: instrument?.let { priceText(it.currentPriceE5) }.orEmpty(),
                feeInput = trade?.let { R.format_units(it.fee_minor, it.currency.fraction_digits) } ?: input.feeInput,
                occurredAt = trade?.let { Instant.ofEpochMilli(it.occurred_at_ms).atZone(clock.zone).toLocalDateTime() } ?: input.occurredAt,
                cashLinked = trade?.cash_linked ?: false, cashAccountId = trade?.cashAccountId,
                name = instrument?.name.orEmpty(), loaded = true, priceUpdatedAtMs = instrument?.priceUpdatedAtMs,
                originalOccurredAtMs = trade?.occurred_at_ms,
                originalCashImpactMinor = if (trade?.cash_linked == true)
                    if (trade.direction == Direction.BUY) -tradeAmountWithFee(trade.direction, trade.amount_minor, trade.fee_minor)
                    else tradeAmountWithFee(trade.direction, trade.amount_minor, trade.fee_minor) else 0))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = state.value.copy(failed = true) }
    }
    private fun change(value: TradeFormUiState) {
        mutable.value = value
        saved["instrumentId"] = value.instrumentId
        saved["positionId"] = value.positionId
        saved["revision"] = value.expectedRevision
        saved["currency"] = value.currency?.code
        saved["direction"] = value.direction.name
        saved["quantity"] = value.quantityInput
        saved["price"] = value.executionPriceInput
        saved["fee"] = value.feeInput
        saved["occurredAt"] = value.occurredAt.toString()
        saved["linked"] = value.cashLinked
        saved["cashAccountId"] = value.cashAccountId
        saved["name"] = value.name
        saved["loaded"] = value.loaded
        saved["originalCashImpact"] = value.originalCashImpactMinor
        saved["setAsCurrentPrice"] = value.setAsCurrentPrice
        saved["priceUpdatedAt"] = value.priceUpdatedAtMs
        saved["originalOccurredAt"] = value.originalOccurredAtMs
    }
    /**
     * A new buy or sell may set its price as the current one, unless it is older than the current price: that would
     * put an old price over a newer update.
     */
    fun canSetCurrentPrice(value: TradeFormUiState = state.value): Boolean = value.mode == TradeFormMode.CREATE &&
        value.direction != Direction.DIVIDEND && value.priceUpdatedAtMs != null &&
        value.occurredAt.atZone(clock.zone).toInstant().toEpochMilli() >= value.priceUpdatedAtMs
    /** When the current price was last updated, in local time, for the reason the option is off. */
    fun priceUpdatedAt(): LocalDateTime? = state.value.priceUpdatedAtMs?.let { Instant.ofEpochMilli(it).atZone(clock.zone).toLocalDateTime() }
    fun update(transform: (TradeFormUiState) -> TradeFormUiState) { if (submission.value.editable) change(transform(state.value)) }
    fun setCashLinked(linked: Boolean) = update { current ->
        val candidates = cashAccounts.value.filter { it.currency == current.currency }
        current.copy(cashLinked = linked,
            cashAccountId = if (!linked) null else current.cashAccountId ?: candidates.singleOrNull()?.id)
    }
    fun selectCashAccount(id: Long) = update { it.copy(cashLinked = true, cashAccountId = id) }
    /**
     * Moves a new trade to [id], holding the instrument as [positionId] if it already does. What was typed stays; the
     * cash account belonged to the old account, so it is cleared to be chosen again.
     */
    fun selectAccount(id: Long, positionId: Long?) {
        val current = state.value
        if (current.mode != TradeFormMode.CREATE || !submission.value.editable || id == account.value) return
        account.value = id
        saved["accountId"] = id
        change(current.copy(positionId = positionId, expectedRevision = null, cashLinked = false, cashAccountId = null))
    }
    fun amountPreview(): String? = runCatching {
        val input = state.value
        val currency = requireNotNull(input.currency)
        val amount = R.amount(R.parse_e8(input.quantityInput, true),
            R.parse_e8(input.executionPriceInput, true), currency, true)
        val fee = R.parse_units(input.feeInput, currency.fraction_digits)
        R.format_units(tradeAmountWithFee(input.direction, amount, fee), currency.fraction_digits) + " " + currency.code
    }.getOrNull()
    fun cashImpactPreview(): String? = runCatching {
        val input = state.value
        val currency = requireNotNull(input.currency)
        val nextImpact = if (input.mode == TradeFormMode.DELETE || !input.cashLinked) 0L else {
            val amount = R.amount(R.parse_e8(input.quantityInput, true), R.parse_e8(input.executionPriceInput, true), currency, true)
            val fee = R.parse_units(input.feeInput, currency.fraction_digits)
            val settled = tradeAmountWithFee(input.direction, amount, fee)
            if (input.direction == Direction.BUY) -settled else settled
        }
        val delta = R.replace_contribution(0, input.originalCashImpactMinor, nextImpact)
        (if (delta > 0) "+" else "") + R.format_units(delta, currency.fraction_digits) + " " + currency.code
    }.getOrNull()
    fun submit() = session.submit {
        val input = state.value
        if (!input.loaded) throw DomainException(ErrorCode.NOT_FOUND)
        val occurredAtMs = keptEpoch(input.occurredAt, clock.zone, input.originalOccurredAtMs)
        when (input.mode) {
            TradeFormMode.CREATE -> RecordInvestmentTrade(operationId, input.positionId ?: 0L,
                input.direction, R.parse_e8(input.quantityInput, true), R.parse_e8(input.executionPriceInput, true), occurredAtMs,
                input.cashLinked, if (input.cashLinked) input.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null,
                R.parse_units(input.feeInput, requireNotNull(input.currency).fraction_digits), account.value, input.instrumentId,
                setAsCurrentPrice = input.setAsCurrentPrice && canSetCurrentPrice(input))
            TradeFormMode.EDIT -> EditInvestmentTrade(operationId, requireNotNull(input.tradeId), requireNotNull(input.expectedRevision),
                input.direction, R.parse_e8(input.quantityInput, true), R.parse_e8(input.executionPriceInput, true), occurredAtMs,
                input.cashLinked, if (input.cashLinked) input.cashAccountId ?: throw DomainException(ErrorCode.WRONG_CASH_ACCOUNT) else null,
                R.parse_units(input.feeInput, requireNotNull(input.currency).fraction_digits))
            TradeFormMode.DELETE -> DeleteInvestmentTrade(operationId, requireNotNull(input.tradeId), requireNotNull(input.expectedRevision))
        }
    }
    fun consumeSuccess(): Boolean {
        if (submission.value.phase != SubmissionPhase.SUCCEEDED || saved.get<Boolean>("consumed") == true) return false
        saved["consumed"] = true
        return true
    }
}
