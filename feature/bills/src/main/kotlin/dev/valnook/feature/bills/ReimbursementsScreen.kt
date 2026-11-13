package dev.valnook.feature.bills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

enum class ReimbursementTab { PENDING, DONE, ALL }

/**
 * What is still owed across every expense to be paid back: how many, their sum in the base currency, and what is
 * left in each expense's own currency for when that sum cannot be made ([partial], or no base currency).
 */
data class ReimbursementSummary(val count: Int, val pending: BigDecimal, val base: Currency?, val partial: Boolean,
    val byCurrency: List<Pair<Currency, Long>> = emptyList())

/** One expense in a batch: what comes back for it, in its own currency and in the receiving account's. */
data class BatchLine(val bill: Bill, val amountText: String, val amountMinor: Long?, val receivedMinor: Long?,
    val rate: BigDecimal?, val rateSource: BillRateSource?)

data class ReimbursementsState(
    val loaded: Boolean = false,
    val tab: ReimbursementTab = ReimbursementTab.PENDING,
    val payer: String? = null,
    val payers: List<String> = emptyList(),
    val months: List<Pair<YearMonth, List<Bill>>> = emptyList(),
    val summary: ReimbursementSummary? = null,
    val categories: Map<Long, BillCategory> = emptyMap(),
    val accounts: List<BillAccountChoice> = emptyList(),
    val selecting: Boolean = false,
    val selected: Set<Long> = emptySet(),
    /** The batch being confirmed: one account and time for all, an amount for each. */
    val confirming: Boolean = false,
    val batchAccountId: Long? = null,
    val batchTime: LocalDateTime? = null,
    val lines: List<BatchLine> = emptyList(),
    val busy: Boolean = false,
    val error: ErrorCode? = null,
) {
    val owed: (Bill) -> Boolean = { it.reimburseState == ReimburseState.PENDING || it.reimburseState == ReimburseState.PARTIAL }
    val batchReady: Boolean get() = !busy && batchAccountId != null && lines.isNotEmpty() && lines.all {
        it.amountMinor != null && it.amountMinor in 1..it.bill.remainingMinor && it.receivedMinor != null && it.receivedMinor > 0 && it.rate != null
    }
}

/** Expenses to be paid back: what is still owed, reimbursements one at a time or several at once. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReimbursementsViewModel(
    private val bills: BillRepository,
    private val commands: FinancialCommands,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private data class Ui(val tab: ReimbursementTab, val payer: String?, val selecting: Boolean = false,
        val selected: Set<Long> = emptySet(), val confirming: Boolean = false, val account: Long? = null,
        val time: LocalDateTime? = null, val amounts: Map<Long, String> = emptyMap(), val busy: Boolean = false,
        val error: ErrorCode? = null)

    private val ui = MutableStateFlow(Ui(saved.get<String>("tab")?.let(ReimbursementTab::valueOf) ?: ReimbursementTab.PENDING,
        saved.get<String>("payer")))
    private val settingsState = settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val fxState = fx.stateIn(viewModelScope, SharingStarted.Eagerly, FxContext())
    private val dayRates = combine(ui.map { it.time?.toLocalDate() }.distinctUntilChanged(), settingsState) { day, s -> day to s?.baseCurrency }
        .mapLatest { (day, base) -> if (day == null || base == null) emptyMap() else runCatching { bills.storedRatesOn(base.code, day) }.getOrDefault(emptyMap()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val state: StateFlow<ReimbursementsState> = combine(
        combine(bills.observeReimbursable(), bills.observeCategories(), bills.observeAccountChoices()) { r, c, a -> Triple(r, c, a) },
        ui, combine(settingsState, fxState, dayRates) { s, x, d -> Triple(s, x, d) },
    ) { (all, categories, accounts), u, (settingsValue, fxValue, day) ->
        val owed = { b: Bill -> b.reimburseState == ReimburseState.PENDING || b.reimburseState == ReimburseState.PARTIAL }
        val shown = all.filter { b ->
            (u.payer == null || b.counterparty.trim() == u.payer) && when (u.tab) {
                ReimbursementTab.PENDING -> owed(b)
                ReimbursementTab.DONE -> b.reimburseState == ReimburseState.DONE
                ReimbursementTab.ALL -> true
            }
        }
        val months = shown.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAtMs).atZone(clock.zone)) }.toList()
            .sortedByDescending { it.first }
        val account = accounts.firstOrNull { it.cashAccountId == u.account }
        val lines = if (!u.confirming) emptyList() else all.filter { it.id in u.selected && owed(it) }.map { b ->
            line(b, u.amounts[b.id] ?: plainInput(b.remainingMinor, b.currency.fraction_digits), account?.currency, settingsValue, fxValue, day)
        }
        ReimbursementsState(true, u.tab, u.payer, all.map { it.counterparty.trim() }.filter { it.isNotEmpty() }.distinct().sorted(),
            months, settingsValue?.let { summary(all, it, fxValue) }, categories.associateBy { it.id }, accounts,
            u.selecting, u.selected, u.confirming, u.account, u.time, lines, u.busy, u.error)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReimbursementsState())

    private fun line(bill: Bill, text: String, to: Currency?, settings: AppSettings?, fx: FxContext, day: Map<String, BigDecimal>): BatchLine {
        val amount = text.trim().toBigDecimalOrNull()?.takeIf { it.scale() <= bill.currency.fraction_digits && it.signum() > 0 }
            ?.movePointRight(bill.currency.fraction_digits)?.let { runCatching { it.longValueExact() }.getOrNull() }
        if (to == null || amount == null) return BatchLine(bill, text, amount, null, null, null)
        val suggestion = when {
            to == bill.currency -> BillRates.Suggestion(BigDecimal.ONE, BillRateSource.SAME)
            bill.accountCurrency == to && bill.rateSource != BillRateSource.SAME -> BillRates.Suggestion(bill.rate, BillRateSource.ONE_TIME)
            else -> settings?.let { BillRates.suggest(bill.currency, to, it, fx, day) }
        }
        val received = suggestion?.let { runCatching { BillRules.convert(amount, bill.currency, to, it.rate) }.getOrNull() }
        return BatchLine(bill, text, amount, received, suggestion?.rate, suggestion?.source)
    }

    private fun update(change: (Ui) -> Ui) { if (!ui.value.busy) ui.value = change(ui.value).copy(error = null) }

    fun setTab(value: ReimbursementTab) { update { it.copy(tab = value, selecting = false, selected = emptySet()) }; saved["tab"] = value.name }
    fun setPayer(value: String?) { update { it.copy(payer = value) }; saved["payer"] = value }
    fun select(on: Boolean) = update { it.copy(selecting = on, selected = emptySet()) }
    fun toggle(id: Long) = update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }

    /** Moves on to the batch's account, time and amounts; the account starts as the one the first expense was paid from. */
    fun confirmBatch() = update { u ->
        val first = state.value.months.flatMap { it.second }.firstOrNull { it.id in u.selected }
        u.copy(confirming = true, account = u.account ?: first?.cashAccountId?.takeIf { id -> state.value.accounts.any { it.cashAccountId == id } },
            time = u.time ?: LocalDateTime.now(clock).withSecond(0).withNano(0))
    }
    fun cancelBatch() = update { it.copy(confirming = false) }
    fun setBatchAccount(id: Long?) = update { it.copy(account = id) }
    fun setBatchTime(value: LocalDateTime) = update { it.copy(time = value.withSecond(0).withNano(0)) }
    fun setBatchDaysAgo(days: Long) = update {
        it.copy(time = LocalDateTime.of(java.time.LocalDate.now(clock).minusDays(days), LocalDateTime.now(clock).toLocalTime().withSecond(0).withNano(0)))
    }
    fun setAmount(id: Long, text: String) = update { it.copy(amounts = it.amounts + (id to text.take(24))) }

    /** Every selected expense gets its reimbursement at once, into one account, as one batch. */
    fun submit() {
        val s = state.value
        if (!s.batchReady) return
        val account = s.batchAccountId ?: return
        val time = s.batchTime ?: return
        ui.value = ui.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                commands.execute(SettleBills(UUID.randomUUID().toString(), account, time.atZone(clock.zone).toInstant().toEpochMilli(),
                    s.lines.map { SettlementItem(it.bill.id, it.bill.revision, it.amountMinor!!, it.receivedMinor!!, it.rate!!, it.rateSource!!) }))
                ui.value = ui.value.copy(busy = false, selecting = false, selected = emptySet(), confirming = false, amounts = emptyMap())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) { ui.value = ui.value.copy(busy = false, error = error.code) }
            catch (_: Exception) { ui.value = ui.value.copy(busy = false, error = ErrorCode.STALE_RECORD) }
        }
    }

    companion object {
        /** An amount as an editable plain number, without grouping. */
        fun plainInput(minor: Long, digits: Int): String = BigDecimal.valueOf(minor, digits).toPlainString()

        /**
         * What is still owed, at today's rate into the base currency: each expense's account amount scaled to what is
         * left of it. [ReimbursementSummary.partial] when some currency has no rate.
         */
        fun summary(all: List<Bill>, settings: AppSettings, fx: FxContext): ReimbursementSummary {
            val resolver = FxRateResolver(settings, fx)
            val owed = all.filter { it.reimburseState == ReimburseState.PENDING || it.reimburseState == ReimburseState.PARTIAL }
            var total = BigDecimal.ZERO
            var partial = false
            owed.forEach { bill ->
                val rate = resolver.today(bill.accountCurrency.code) ?: run { partial = true; return@forEach }
                val left = BigDecimal.valueOf(bill.accountAmountMinor, bill.accountCurrency.fraction_digits).multiply(rate)
                    .multiply(BigDecimal.valueOf(bill.remainingMinor)).divide(BigDecimal.valueOf(bill.paidMinor.coerceAtLeast(1)), 12, RoundingMode.HALF_UP)
                total += left
            }
            val digits = settings.baseCurrency?.fraction_digits ?: 2
            val byCurrency = owed.groupBy { it.currency }.map { (currency, list) -> currency to list.sumOf { it.remainingMinor } }
                .sortedBy { it.first.code }
            return ReimbursementSummary(owed.size, total.setScale(digits, RoundingMode.HALF_UP), settings.baseCurrency,
                partial || settings.baseCurrency == null, byCurrency)
        }

        /** Every expense still owed, summed up as the settings page shows it. */
        fun observeSummary(bills: BillRepository, settings: Flow<AppSettings>, fx: Flow<FxContext>): Flow<ReimbursementSummary> =
            combine(bills.observeReimbursable(), settings, fx) { all, s, x -> summary(all, s, x) }.distinctUntilChanged()
    }
}

/** "3 bills · 428.00 CNY" for the settings page, or that nothing is owed. */
@Composable fun reimbursementSummaryText(summary: ReimbursementSummary?): String = when {
    summary == null || summary.count == 0 -> stringResource(R.string.reimbursements_none)
    else -> stringResource(R.string.reimbursements_summary, summary.count, pendingText(summary))
}

/** The base-currency sum when every currency converts; otherwise what is left in each currency, as "780.00 CNY + 20.00 USD". */
fun pendingText(summary: ReimbursementSummary): String {
    val base = summary.base
    return if (base != null && !summary.partial)
        "${plainAmount(summary.pending.movePointRight(base.fraction_digits).toLong(), base.fraction_digits)} ${base.code}"
    else summary.byCurrency.joinToString(" + ") { (currency, minor) -> "${plainAmount(minor, currency.fraction_digits)} ${currency.code}" }
        .ifEmpty { "0" }
}

@Composable fun ReimbursementsScreen(vm: ReimbursementsViewModel, onOpen: (Long) -> Unit, onReimburse: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) { PageLoading(); return }
    BackHandler(state.confirming || state.selecting) { if (state.confirming) vm.cancelBatch() else vm.select(false) }
    if (state.confirming) { BatchConfirm(state, vm); return }
    val accounts = remember(state.accounts) { state.accounts.associateBy { it.cashAccountId } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("reimbursements"), contentPadding = pageContentPadding(),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(stringResource(R.string.reimbursements_pending_total), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val s = state.summary
                    Text(s?.let(::pendingText) ?: "—", Modifier.testTag("reimbursements-total"),
                        style = if ((s?.byCurrency?.size ?: 0) > 1 && s?.partial == true) MaterialTheme.typography.titleLarge
                            else MaterialTheme.typography.headlineMedium)
                }
            }
            item {
                CapsuleChoiceRow(ReimbursementTab.entries, state.tab, vm::setTab,
                    optionModifier = { Modifier.testTag("reimbursements-tab-${it.name}") }) {
                    Text(stringResource(when (it) {
                        ReimbursementTab.PENDING -> R.string.reimbursements_tab_pending
                        ReimbursementTab.DONE -> R.string.reimbursements_tab_done
                        ReimbursementTab.ALL -> R.string.reimbursements_tab_all
                    }))
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        if (state.payers.isNotEmpty()) {
                            FilterChip(state.payer == null, { vm.setPayer(null) }, { Text(stringResource(R.string.reimbursements_all_payers)) })
                            state.payers.forEach { payer ->
                                FilterChip(state.payer == payer, { vm.setPayer(if (state.payer == payer) null else payer) }, { Text(payer) },
                                    Modifier.testTag("reimbursements-payer-$payer"))
                            }
                        }
                    }
                    if (state.tab == ReimbursementTab.PENDING && state.months.isNotEmpty()) TextButton({ vm.select(!state.selecting) },
                        Modifier.testTag("reimbursements-select")) {
                        Text(stringResource(if (state.selecting) R.string.bill_cancel else R.string.reimbursements_batch))
                    }
                }
            }
            if (state.months.isEmpty()) item { EmptyState(stringResource(R.string.reimbursements_empty)) }
            state.months.forEach { (month, list) ->
                item(key = "month-$month") {
                    Text(stringResource(R.string.reimbursements_month, month.year, month.monthValue),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item(key = "list-$month") {
                    GlassCard {
                        Column {
                            list.forEachIndexed { index, bill ->
                                val owed = state.owed(bill)
                                ReimbursementRow(bill, state, accounts, state.selecting && owed, bill.id in state.selected) {
                                    when {
                                        state.selecting && owed -> vm.toggle(bill.id)
                                        owed -> onReimburse(bill.id)
                                        else -> onOpen(bill.id)
                                    }
                                }
                                if (index < list.lastIndex) FormDivider()
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(72.dp)) }
        }
        if (state.selecting) Button(vm::confirmBatch, Modifier.align(Alignment.BottomCenter)
            .padding(bottom = LocalPageBottomSpace.current + 16.dp).testTag("reimbursements-batch-next"), enabled = state.selected.isNotEmpty()) {
            Text(stringResource(R.string.reimbursements_batch_next, state.selected.size))
        }
    }
}

@Composable private fun ReimbursementRow(bill: Bill, state: ReimbursementsState, accounts: Map<Long, BillAccountChoice>,
    selectable: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val category = (bill.subCategoryId ?: bill.categoryId)?.let(state.categories::get)
    val digits = bill.currency.fraction_digits
    val day = Instant.ofEpochMilli(bill.occurredAtMs).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
        .testTag("reimbursement-${bill.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        if (selectable) Checkbox(selected, { onClick() }, Modifier.size(24.dp))
        CategoryIcon(category?.iconKey ?: "circle-question", category?.colorKey ?: "grey", size = 38.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(billName(bill, state.categories), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(stringResource(R.string.reimbursements_day, day.monthValue, day.dayOfMonth),
                bill.counterparty.takeIf { it.isNotBlank() }, sideName(bill.cashAccountId, accounts, false, "", bill.accountDeleted))
                .joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${plainAmount(bill.paidMinor, digits)} ${bill.currency.code}", style = MaterialTheme.typography.bodyMedium)
            Text(when (bill.reimburseState) {
                ReimburseState.PENDING, ReimburseState.PARTIAL -> stringResource(R.string.bill_pending_tag, plainAmount(bill.remainingMinor, digits))
                ReimburseState.WAIVED -> stringResource(R.string.bill_waived_tag)
                else -> stringResource(R.string.bill_reimbursed_tag, plainAmount(bill.reimbursedMinor, digits))
            }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
    }
}

/** One account and one time for the whole batch; each expense's amount can be lowered for a part payment. */
@Composable private fun BatchConfirm(state: ReimbursementsState, vm: ReimbursementsViewModel) {
    var picking by remember { mutableStateOf(false) }
    var timeOpen by remember { mutableStateOf(false) }
    val accounts = remember(state.accounts) { state.accounts.associateBy { it.cashAccountId } }
    val to = state.batchAccountId?.let(accounts::get)?.currency
    LazyColumn(Modifier.fillMaxSize().testTag("reimbursements-batch"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Text(stringResource(R.string.reimbursements_batch_title, state.lines.size), style = MaterialTheme.typography.titleMedium) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                Pill(state.batchAccountId?.let { accounts[it]?.label() } ?: stringResource(R.string.bill_choose_account),
                    selected = state.batchAccountId != null, onClick = { picking = true }, tag = "reimbursements-batch-account")
                state.batchTime?.let { Pill(timeText(it), selected = false, onClick = { timeOpen = true }, tag = "reimbursements-batch-time") }
            }
        }
        items(state.lines, key = { it.bill.id }) { line ->
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text(billName(line.bill, state.categories), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    GlassTextField(line.amountText, { vm.setAmount(line.bill.id, it) },
                        stringResource(R.string.reimbursements_batch_amount, line.bill.currency.code,
                            plainAmount(line.bill.remainingMinor, line.bill.currency.fraction_digits)),
                        Modifier.fillMaxWidth().testTag("reimbursements-batch-amount-${line.bill.id}"), keyboardType = KeyboardType.Decimal,
                        isError = line.amountMinor == null || line.amountMinor > line.bill.remainingMinor)
                    if (to != null && to != line.bill.currency) Text(
                        line.receivedMinor?.let { stringResource(R.string.reimbursements_batch_received, plainAmount(it, to.fraction_digits), to.code) }
                            ?: stringResource(R.string.reimbursements_batch_no_rate, line.bill.currency.code, to.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.receivedMinor == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            ErrorMessage(state.error?.name)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TextButton(vm::cancelBatch, Modifier.weight(1f)) { Text(stringResource(R.string.bill_cancel)) }
                Button(vm::submit, Modifier.weight(1f).testTag("reimbursements-batch-confirm"), enabled = state.batchReady) {
                    Text(stringResource(R.string.bill_confirm))
                }
            }
        }
    }
    if (picking) BillAccountPicker(state.accounts, state.batchAccountId, option = null, optionSelected = false,
        onPick = { vm.setBatchAccount(it); picking = false }, onFavorites = {}, onDismiss = { picking = false })
    if (timeOpen) state.batchTime?.let { TimeDialog(it, onDaysAgo = vm::setBatchDaysAgo, onChange = vm::setBatchTime, onDismiss = { timeOpen = false }) }
}
