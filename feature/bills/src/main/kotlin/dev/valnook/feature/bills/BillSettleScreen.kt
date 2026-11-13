package dev.valnook.feature.bills

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillRepository
import dev.valnook.domain.repository.FinancialCommands
import dev.valnook.domain.repository.SettleBill
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

enum class SettleField { AMOUNT, RECEIVED }

/**
 * A refund or reimbursement being written: [amount] in the bill's own currency; [received], when typed, is what the
 * account actually got and sets the rate. Null [cashAccountId] until one is chosen.
 */
data class SettleForm(
    val amount: BillExpression = BillExpression(),
    val cashAccountId: Long? = null,
    val received: BillExpression? = null,
    val occurredAt: LocalDateTime,
    val note: String = "",
    val target: SettleField = SettleField.AMOUNT,
)

enum class SettleProblem { AMOUNT, OVER, ACCOUNT, RATE }

data class SettleState(
    val type: BillType,
    val loaded: Boolean = false,
    val parent: Bill? = null,
    val categories: Map<Long, BillCategory> = emptyMap(),
    val accounts: List<BillAccountChoice> = emptyList(),
    val form: SettleForm,
    val amountMinor: Long? = null,
    val accountCurrency: Currency? = null,
    val rate: BigDecimal? = null,
    val rateSource: BillRateSource? = null,
    val receivedMinor: Long? = null,
    val problem: SettleProblem? = null,
    val busy: Boolean = false,
    val error: ErrorCode? = null,
    val done: Boolean = false,
) {
    val crossCurrency: Boolean get() = parent != null && accountCurrency != null && accountCurrency != parent.currency
}

/** Money coming back for an expense ([type] REFUND or REIMBURSEMENT), up to what is left of it. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillSettleViewModel(
    private val parentId: Long,
    private val type: BillType,
    private val bills: BillRepository,
    private val commands: FinancialCommands,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val form = MutableStateFlow(SettleForm(
        amount = BillExpression(saved.get<String>("amount") ?: ""),
        cashAccountId = saved.get<Long>("account"),
        received = saved.get<String>("received")?.let(::BillExpression),
        occurredAt = saved.get<String>("time")?.let(LocalDateTime::parse) ?: LocalDateTime.now(clock).withSecond(0).withNano(0),
        note = saved.get<String>("note") ?: ""))
    private val action = MutableStateFlow(Triple(false, null as ErrorCode?, false))
    private var started = saved.get<Boolean>("started") ?: false
    private var operationId = saved.get<String>("operation") ?: UUID.randomUUID().toString().also { saved["operation"] = it }
    private val settingsState = settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val fxState = fx.stateIn(viewModelScope, SharingStarted.Eagerly, FxContext())
    private val dayRates = combine(form.map { it.occurredAt.toLocalDate() }.distinctUntilChanged(), settingsState) { day, s -> day to s?.baseCurrency }
        .mapLatest { (day, base) -> if (base == null) emptyMap() else runCatching { bills.storedRatesOn(base.code, day) }.getOrDefault(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val state: StateFlow<SettleState> = combine(
        combine(bills.observeBill(parentId), bills.observeCategories(), bills.observeAccountChoices()) { p, c, a -> Triple(p, c, a) },
        form, action, combine(settingsState, fxState, dayRates) { s, x, d -> Triple(s, x, d) },
    ) { (parent, categories, accounts), f, (busy, error, done), (settingsValue, fxValue, day) ->
        // Starts from what is left and the account the bill was paid from, once it is known.
        val start = if (!started && parent != null) {
            started = true; saved["started"] = true
            f.copy(amount = if (f.amount.isEmpty) BillExpression.of(parent.remainingMinor.coerceAtLeast(0), parent.currency.fraction_digits) else f.amount,
                cashAccountId = f.cashAccountId ?: parent.cashAccountId?.takeIf { id -> accounts.any { it.cashAccountId == id } })
                .also { form.value = it; remember(it) }
        } else f
        compute(SettleState(type, parent != null || done, parent, categories.associateBy { it.id }, accounts, start,
            busy = busy, error = error, done = done), settingsValue, fxValue, day)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettleState(type, form = form.value))

    private fun compute(s: SettleState, settings: AppSettings?, fx: FxContext, day: Map<String, BigDecimal>): SettleState {
        val parent = s.parent ?: return s
        val f = s.form
        val currency = parent.currency
        val amount = f.amount.minor(currency.fraction_digits)?.takeIf { it > 0 }
        val account = s.accounts.firstOrNull { it.cashAccountId == f.cashAccountId }
        val to = account?.currency
        var rate: BigDecimal? = null
        var source: BillRateSource? = null
        var received: Long? = null
        if (amount != null && to != null) {
            val typed = f.received?.minor(to.fraction_digits)?.takeIf { it > 0 }
            when {
                to == currency -> { rate = BigDecimal.ONE; source = BillRateSource.SAME; received = amount }
                typed != null -> {
                    received = typed; source = BillRateSource.DERIVED
                    rate = runCatching { BillRules.derivedRate(amount, currency, typed, to) }.getOrNull()
                }
                else -> {
                    // Back the way it went: the bill's own rate; otherwise the day's.
                    val suggestion = if (parent.accountCurrency == to && parent.rateSource != BillRateSource.SAME)
                        BillRates.Suggestion(parent.rate, BillRateSource.ONE_TIME)
                    else settings?.let { BillRates.suggest(currency, to, it, fx, day) }
                    rate = suggestion?.rate; source = suggestion?.source
                    received = suggestion?.let { runCatching { BillRules.convert(amount, currency, to, it.rate) }.getOrNull() }
                }
            }
        }
        val problem = when {
            amount == null || f.amount.hasOperator -> SettleProblem.AMOUNT
            amount > parent.remainingMinor -> SettleProblem.OVER
            account == null -> SettleProblem.ACCOUNT
            rate == null || received == null || received <= 0 -> SettleProblem.RATE
            else -> null
        }
        return s.copy(amountMinor = amount, accountCurrency = to, rate = rate, rateSource = source, receivedMinor = received,
            problem = problem)
    }

    private fun remember(f: SettleForm) {
        saved["amount"] = f.amount.text; saved["account"] = f.cashAccountId; saved["received"] = f.received?.text
        saved["time"] = f.occurredAt.toString(); saved["note"] = f.note
    }

    private fun update(transform: (SettleForm) -> SettleForm) {
        if (action.value.first || action.value.third) return
        form.value = transform(form.value).also(::remember)
        if (action.value.second != null) action.value = Triple(false, null, false)
    }

    fun focus(field: SettleField) = update { it.copy(target = field) }

    fun press(key: BillKey) = update { f ->
        val s = state.value
        when (f.target) {
            SettleField.AMOUNT -> f.copy(amount = f.amount.press(key, s.parent?.currency?.fraction_digits ?: 2))
            SettleField.RECEIVED -> f.copy(received = (f.received ?: BillExpression()).press(key, s.accountCurrency?.fraction_digits ?: 2))
        }
    }

    /** Everything that is left of the bill. */
    fun full() = update { f -> state.value.parent?.let { f.copy(amount = BillExpression.of(it.remainingMinor.coerceAtLeast(0), it.currency.fraction_digits),
        target = SettleField.AMOUNT) } ?: f }

    /** A new account converts again from the rate, not from an amount typed for the old one. */
    fun chooseAccount(id: Long?) = update { it.copy(cashAccountId = id, received = null, target = SettleField.AMOUNT) }

    fun useRate() = update { it.copy(received = null, target = SettleField.AMOUNT) }
    fun setTime(value: LocalDateTime) = update { it.copy(occurredAt = value.withSecond(0).withNano(0)) }
    fun setDaysAgo(days: Long) = update {
        it.copy(occurredAt = LocalDateTime.of(java.time.LocalDate.now(clock).minusDays(days), LocalDateTime.now(clock).toLocalTime().withSecond(0).withNano(0)))
    }
    fun setNote(value: String) = update { it.copy(note = value.take(BillRules.MAX_NOTE_LENGTH)) }

    /** A typed sum is worked out first, as on the bill form; then the money comes back. */
    fun confirm() {
        val s = state.value
        val f = s.form
        val current = if (f.target == SettleField.AMOUNT) f.amount else f.received
        if (current?.hasOperator == true) {
            update { when (it.target) {
                SettleField.AMOUNT -> it.copy(amount = it.amount.settled(s.parent?.currency?.fraction_digits ?: 2))
                SettleField.RECEIVED -> it.copy(received = it.received?.settled(s.accountCurrency?.fraction_digits ?: 2))
            } }
            return
        }
        val parent = s.parent ?: return
        if (s.problem != null || action.value.first) return
        action.value = Triple(true, null, false)
        viewModelScope.launch {
            try {
                commands.execute(SettleBill(operationId, parent.id, parent.revision, type, SettlementDraft(
                    f.occurredAt.atZone(clock.zone).toInstant().toEpochMilli(), s.amountMinor!!, f.cashAccountId!!,
                    s.receivedMinor!!, s.rate!!, s.rateSource!!, f.note)))
                action.value = Triple(false, null, true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) {
                // A refused request may be sent again once corrected, as a new operation.
                operationId = UUID.randomUUID().toString().also { saved["operation"] = it }
                action.value = Triple(false, error.code, false)
            } catch (_: Exception) { action.value = Triple(false, ErrorCode.STALE_RECORD, false) }
        }
    }
}

@Composable fun BillSettleScreen(vm: BillSettleViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.done) { if (state.done) onDone() }
    if (!state.loaded) { PageLoading(); return }
    val parent = state.parent ?: run { EmptyState(stringResource(R.string.bill_unavailable)); return }
    val f = state.form
    var picking by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val accounts = remember(state.accounts) { state.accounts.associateBy { it.cashAccountId } }
    val currency = parent.currency
    Column(Modifier.fillMaxSize().testTag("bill-settle")) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Spacer(Modifier.height(LocalPageTopSpace.current))
            ParentSummary(parent, state)
            FigureBox(stringResource(typeLabel(state.type)), amountText(f.amount, currency.fraction_digits),
                f.target == SettleField.AMOUNT, { vm.focus(SettleField.AMOUNT) }, large = true, tag = "bill-settle-amount",
                leading = { Text(currency.code, style = MaterialTheme.typography.titleMedium) },
                expression = f.amount.text.takeIf { f.amount.hasOperator })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.bill_settle_left, plainAmount(parent.remainingMinor, currency.fraction_digits), currency.code),
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(vm::full, Modifier.testTag("bill-settle-full")) { Text(stringResource(R.string.bill_settle_full)) }
            }
            val to = state.accountCurrency
            if (state.crossCurrency && to != null) {
                val typed = f.received
                FigureBox(stringResource(R.string.bill_settle_received, to.code),
                    if (typed != null) amountText(typed, to.fraction_digits)
                    else state.receivedMinor?.let { plainAmount(it, to.fraction_digits) } ?: "",
                    f.target == SettleField.RECEIVED, { vm.focus(SettleField.RECEIVED) }, tag = "bill-settle-received")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.rate?.let { "1 ${currency.code} = ${it.stripTrailingZeros().toPlainString()} ${to.code}" }
                        ?: stringResource(R.string.bill_settle_no_rate, currency.code, to.code),
                        Modifier.weight(1f).testTag("bill-settle-rate"), style = MaterialTheme.typography.bodySmall,
                        color = if (state.rate == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (typed != null) TextButton(vm::useRate) { Text(stringResource(R.string.bill_settle_use_rate)) }
                }
            }
            Spacer(Modifier.height(Space.sm))
        }
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val problem = when {
                state.error != null -> errorText(state.error!!.name)
                state.problem == SettleProblem.OVER -> stringResource(R.string.bill_settle_over)
                state.problem == SettleProblem.ACCOUNT -> stringResource(R.string.bill_settle_choose_account)
                // A missing rate is said beside the amount received.
                else -> null
            }
            if (problem != null) Text(problem, Modifier.padding(horizontal = 16.dp).testTag("bill-settle-problem"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
                Pill(f.cashAccountId?.let { accounts[it]?.label() } ?: stringResource(R.string.bill_choose_account),
                    selected = f.cashAccountId != null, onClick = { picking = true }, tag = "bill-settle-account")
                Pill(timeText(f.occurredAt), selected = false, onClick = { timeOpen = true }, tag = "bill-settle-time")
            }
            Box(Modifier.padding(horizontal = 16.dp)) {
                NoteBox(f.note, vm::setNote, stringResource(R.string.bill_note), "bill-settle-note", Modifier.fillMaxWidth())
            }
        }
        val current = if (f.target == SettleField.AMOUNT) f.amount else f.received ?: BillExpression()
        if (!keyboardOpen) BillKeypad(
            confirmLabel = if (current.hasOperator) "=" else stringResource(R.string.bill_confirm),
            confirmEnabled = !state.busy && (current.hasOperator || state.problem == null),
            againEnabled = false, onKey = vm::press, onConfirm = vm::confirm, onAgain = {},
            bottomSpace = LocalPageBottomSpace.current)
    }
    if (picking) BillAccountPicker(state.accounts, f.cashAccountId, option = null, optionSelected = false,
        onPick = { id -> vm.chooseAccount(id); picking = false }, onFavorites = {}, onDismiss = { picking = false })
    if (timeOpen) TimeDialog(f.occurredAt, onDaysAgo = vm::setDaysAgo, onChange = vm::setTime, onDismiss = { timeOpen = false })
}

/** The bill the money comes back for: its icon, name, what it paid and what already came back. */
@Composable private fun ParentSummary(parent: Bill, state: SettleState) {
    val category = parent.categoryId?.let(state.categories::get)
    val sub = parent.subCategoryId?.let(state.categories::get)
    val shown = sub ?: category
    val name = parent.note.takeIf { it.isNotBlank() }
        ?: if (category != null && sub != null) categoryName(category) + "-" + categoryName(sub) else categoryName(shown)
    val digits = parent.currency.fraction_digits
    GlassCard {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            CategoryIcon(shown?.iconKey ?: "circle-question", shown?.colorKey ?: "grey", size = 38.dp)
            Column(Modifier.weight(1f)) {
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(
                    stringResource(R.string.bill_paid) + " " + plainAmount(parent.paidMinor, digits) + " " + parent.currency.code,
                    if (parent.refundedMinor > 0) stringResource(R.string.bill_refunded_tag, plainAmount(parent.refundedMinor, digits)) else null,
                    if (parent.reimbursedMinor > 0) stringResource(R.string.bill_reimbursed_tag, plainAmount(parent.reimbursedMinor, digits)) else null)
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
