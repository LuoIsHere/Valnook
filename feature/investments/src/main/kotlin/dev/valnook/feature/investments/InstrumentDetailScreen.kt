package dev.valnook.feature.investments

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun signed(value: BigDecimal?, currency: Currency, withCode: Boolean = false): String = value?.let {
    (if (displayedSign(it, currency.fraction_digits) > 0) "+" else "") +
        DecimalRules.format_display(it, currency.fraction_digits) + (if (withCode) " " + currency.code else "")
} ?: "—"

internal fun averageText(value: BigDecimal?): String =
    value?.setScale(5, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "—"

private fun Trade.localDate(): LocalDate = Instant.ofEpochMilli(occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * An instrument, whichever page opened it: everything it adds up to over all accounts, each account's share, its
 * recent prices and its trades. [contextAccountId] is the account it was opened from, chosen first when buying or
 * selling; it changes nothing else.
 */
@Composable fun InstrumentDetail(vm: InstrumentDetailViewModel, contextAccountId: Long?, onTitleReserve: (Int) -> Unit,
    onTrade: (accountId: Long, tradeId: Long) -> Unit, onBuy: (accountId: Long, positionId: Long?) -> Unit,
    onSell: (accountId: Long, positionId: Long) -> Unit, onDividend: (accountId: Long, positionId: Long) -> Unit = { _, _ -> }) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        InstrumentDetailState.Loading -> PageLoading()
        InstrumentDetailState.Failed -> Text(stringResource(R.string.instrument_load_failed))
        InstrumentDetailState.Missing -> EmptyState(stringResource(R.string.instrument_not_found))
        is InstrumentDetailState.Ready -> InstrumentDetailContent(vm, current, contextAccountId, onTitleReserve,
            onTrade, onBuy, onSell, onDividend)
    }
}

private enum class Picking { BUY, SELL, DIVIDEND }

@Composable private fun InstrumentDetailContent(vm: InstrumentDetailViewModel, ready: InstrumentDetailState.Ready,
    contextAccountId: Long?, onTitleReserve: (Int) -> Unit, onTrade: (Long, Long) -> Unit,
    onBuy: (Long, Long?) -> Unit, onSell: (Long, Long) -> Unit, onDividend: (Long, Long) -> Unit) {
    val trades by vm.trades.collectAsStateWithLifecycle()
    // Grouped once per page of trades, not on every recomposition while the header scrolls.
    val tradeDays = remember(trades.rows) { trades.rows.groupBy { it.localDate() } }
    val priceEditor by vm.priceEditor.collectAsStateWithLifecycle()
    val month by vm.month.collectAsStateWithLifecycle()
    val filter by vm.accountFilter.collectAsStateWithLifecycle()
    val instrument = ready.instrument
    val currency = instrument.currency
    val listState = rememberLazyListState()
    var shownMonth by rememberSaveable { mutableStateOf(month) }
    LaunchedEffect(month) { if (shownMonth != month) { listState.scrollToItem(0); shownMonth = month } }
    var picking by rememberSaveable { mutableStateOf<Picking?>(null) }
    val held = ready.holdings.filter { it.position.holding_quantity_e8 > 0 }
    fun buy() {
        // With one account there is nothing to choose.
        if (ready.accounts.size == 1) onBuy(ready.accounts[0].id,
            ready.holdings.firstOrNull { it.account.id == ready.accounts[0].id }?.position?.id)
        else picking = Picking.BUY
    }
    fun sell() { if (held.size == 1) onSell(held[0].account.id, held[0].position.id) else picking = Picking.SELL }
    // Any account that has held it may receive one, sold out or not.
    fun dividend() {
        if (ready.holdings.size == 1) onDividend(ready.holdings[0].account.id, ready.holdings[0].position.id)
        else picking = Picking.DIVIDEND
    }
    val collapse = rememberHeaderCollapse("instrument-${instrument.id}")
    val accountNames = remember(ready.holdings) { ready.holdings.associate { it.position.id to it.account } }
    Column(Modifier.fillMaxSize().nestedScroll(collapse)) {
        InstrumentHeader(collapse, ready, onTitleReserve)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.background)
            .testTag("instrument-detail-list"), state = listState, contentPadding = pageContentPadding()) {
            item("summary") { HoldingSummary(instrument, ready.totals, vm::openPriceEditor) }
            item("actions") {
                Row(Modifier.fillMaxWidth().padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(::buy, Modifier.weight(1f).testTag("instrument-buy"), enabled = ready.accounts.isNotEmpty()) {
                        Text(stringResource(R.string.investment_buy)) }
                    ActionButton(::sell, Modifier.weight(1f).testTag("instrument-sell"), enabled = held.isNotEmpty()) {
                        Text(stringResource(R.string.investment_sell)) }
                    ActionButton(::dividend, Modifier.weight(1f).testTag("instrument-dividend"), enabled = ready.holdings.isNotEmpty()) {
                        Text(stringResource(R.string.investment_dividend), maxLines = 1) }
                }
            }
            item("accounts") { AccountHoldings(ready.holdings, currency, filter) { id -> vm.selectAccount(if (filter == id) null else id) } }
            item("history-header") {
                Row(Modifier.fillMaxWidth().padding(top = Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.investment_trade_history), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    AccountFilter(ready.holdings.map { it.account }, filter, vm::selectAccount)
                }
            }
            item("month") { MonthPager(java.time.YearMonth.parse(month), vm::selectMonth, label = ::localMonthLabel) }
            if (trades.loaded && trades.rows.isEmpty()) item("empty") {
                GlassCard(Modifier.padding(top = 4.dp).testTag("instrument-month-empty")) {
                    Text(stringResource(if (trades.failed) R.string.investment_read_failed else R.string.investment_no_trades),
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center)
                }
            }
            // One card per day, newest first, as the pages arrive.
            tradeDays.forEach { (date, rows) ->
                item("day-$date") { TradeDay(date, rows, accountNames) { trade ->
                    accountNames[trade.investment_id]?.let { onTrade(it.id, trade.id) } } }
            }
            if (trades.hasMore) item("more") {
                LaunchedEffect(trades.rows.size, month) { vm.loadMore() }
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
    priceEditor?.let { editor -> PriceDialog(editor, vm::savePrice, vm::closePriceEditor) }
    picking?.let { mode ->
        val choices = when (mode) {
            Picking.BUY -> ready.accounts
            Picking.SELL -> held.map { it.account }
            Picking.DIVIDEND -> ready.holdings.map { it.account }
        }
        AccountPicker(mode, choices, ready.holdings, contextAccountId, currency, { picking = null }) { account ->
            picking = null
            val position = ready.holdings.firstOrNull { it.account.id == account.id }?.position
            when (mode) {
                Picking.BUY -> onBuy(account.id, position?.id)
                Picking.SELL -> position?.let { onSell(account.id, it.id) }
                Picking.DIVIDEND -> position?.let { onDividend(account.id, it.id) }
            }
        }
    }
}

/** The total value over every account collapses into the title bar; above it the floating result, or the touched price. */
@Composable private fun InstrumentHeader(collapse: HeaderCollapse, ready: InstrumentDetailState.Ready, onTitleReserve: (Int) -> Unit) {
    val instrument = ready.instrument
    val currency = instrument.currency
    val locale = LocalConfiguration.current.locales[0]
    // Before any recorded price, today's price stands alone.
    val prices = ready.prices.ifEmpty { listOf(InstrumentPricePoint(instrument.priceUpdatedAtMs, instrument.currentPriceE5)) }
    val (points, positions) = remember(prices) { pricePlot(prices) }
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let(points::getOrNull)
    val totals = ready.totals
    CollapsingBalanceHeader(collapse, money(totals.marketValue, currency), MaterialTheme.colorScheme.onSurface, onTitleReserve,
        Modifier.testTag("instrument-header"), Modifier.testTag("instrument-value"),
        top = { fading ->
            Column(fading.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(listOf(instrument.symbol, currency.code, typeLabel(instrument.typeName)).filter { it.isNotBlank() }.joinToString(" · "),
                    Modifier.testTag("instrument-code"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().heightIn(min = 20.dp).semantics(mergeDescendants = true) {}
                    .testTag("instrument-header-line"), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val small = MaterialTheme.typography.bodySmall
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    if (selected != null) Text(shortDate(selected.atMs, locale) + " · " + priceText(selected.priceE5) + " " + currency.code,
                        style = small, color = muted, maxLines = 1)
                    else {
                        Text(stringResource(R.string.investment_unrealized), style = small, color = muted, maxLines = 1)
                        val percent = totals.unrealizedPercent?.let { " (" + (if (displayedSign(it, 2) > 0) "+" else "") +
                            it.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%)" }.orEmpty()
                        Text(signed(totals.unrealized, currency) + percent, style = small.copy(fontFeatureSettings = "tnum"),
                            color = profitColor(displayedSign(totals.unrealized, currency.fraction_digits)), maxLines = 1)
                    }
                }
            }
        }) { chartModifier ->
        InstrumentPriceTrend(points, positions, currency, selectedIndex, { index ->
            // Ignored while closing: the chart is on its way out and may sit under the finger.
            selectedIndex = index.takeIf { collapse.progress <= 0f }
        }, { (1f - collapse.progress * 7f).coerceAtLeast(0f) }, chartModifier)
    }
}

@Composable private fun Metric(label: String, value: String, modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    // Read as one: the name, then its value.
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1)
    }
}

/** Units, average and cost over every account, what they realized, and today's price with its update button. */
@Composable private fun HoldingSummary(instrument: Instrument, totals: InstrumentTotals, onPrice: () -> Unit) {
    val currency = instrument.currency
    val locale = LocalConfiguration.current.locales[0]
    GlassCard(Modifier.testTag("instrument-summary")) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(stringResource(R.string.instrument_quantity_label), DecimalRules.format_e8(totals.quantityE8),
                    Modifier.weight(1f).testTag("instrument-quantity"))
                Metric(stringResource(R.string.instrument_average_label), averageText(totals.average),
                    Modifier.weight(1f).testTag("instrument-average"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric(stringResource(R.string.instrument_cost_label), totals.cost?.let { DecimalRules.format_display(it, currency.fraction_digits) } ?: "—",
                    Modifier.weight(1f).testTag("instrument-cost"))
                Column(Modifier.weight(1f)) {
                    Metric(stringResource(R.string.instrument_realized_label), signed(totals.realized, currency),
                        Modifier.testTag("instrument-realized"), profitColor(displayedSign(totals.realized, currency.fraction_digits)))
                    // What dividends added, so the realized figure can be checked against statements.
                    if (totals.dividends.signum() > 0) Text(stringResource(R.string.instrument_dividends_part,
                        DecimalRules.format_display(totals.dividends, currency.fraction_digits)),
                        Modifier.testTag("instrument-dividends"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.instrument_price_line, priceText(instrument.currentPriceE5) + " " + currency.code,
                    shortDate(instrument.priceUpdatedAtMs, locale)), Modifier.weight(1f).testTag("instrument-price"),
                    style = MaterialTheme.typography.bodyMedium)
                ActionButton(onPrice, Modifier.heightIn(min = 36.dp).testTag("instrument-price-update")) {
                    Text(stringResource(R.string.instrument_price_update_action)) }
            }
            Text(stringResource(R.string.investment_cost_includes_fees), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Each account's share; a tap shows only its trades below, a second tap all of them again. */
@Composable private fun AccountHoldings(holdings: List<InstrumentHolding>, currency: Currency, filter: Long?, onAccount: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.lg), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.instrument_accounts_title), Modifier.padding(start = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium)
        GlassCard(Modifier.testTag("instrument-accounts")) {
            if (holdings.isEmpty()) Text(stringResource(R.string.instrument_no_holdings),
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 18.dp), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            holdings.forEachIndexed { index, holding ->
                if (index > 0) FormDivider()
                AccountHoldingRow(holding, currency, filter == holding.account.id) { onAccount(holding.account.id) }
            }
        }
    }
}

@Composable private fun AccountHoldingRow(holding: InstrumentHolding, currency: Currency, chosen: Boolean, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val small = MaterialTheme.typography.bodySmall
    val open = holding.position.holding_quantity_e8 > 0
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .background(if (chosen) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
        .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { selected = chosen }
        .testTag("instrument-account-${holding.account.id}").padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AccountAvatar(holding.account.icon.symbol, holding.account.icon.imageKey, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(holding.account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (open) MaterialTheme.colorScheme.onSurface else muted)
            Text(if (open) stringResource(R.string.instrument_holding_cost, DecimalRules.format_e8(holding.position.holding_quantity_e8),
                averageText(holding.profit.average_cost)) else stringResource(R.string.instrument_closed_position),
                style = small, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (open) {
                Text(DecimalRules.format_display(holding.marketValue, currency.fraction_digits),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                Text(signed(holding.profit.unrealized, currency), style = small.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                    color = profitColor(displayedSign(holding.profit.unrealized, currency.fraction_digits)))
            } else Text(stringResource(R.string.instrument_realized_short, signed(holding.profit.realized, currency)),
                style = small.copy(fontFeatureSettings = "tnum"), maxLines = 1,
                color = profitColor(displayedSign(holding.profit.realized, currency.fraction_digits)))
        }
    }
}

/** Beside the history's title: all accounts, or the one whose trades are shown. */
@Composable private fun AccountFilter(accounts: List<SavingsAccount>, filter: Long?, onChoose: (Long?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = stringResource(R.string.instrument_all_accounts)
    val shown = accounts.firstOrNull { it.id == filter }?.name ?: all
    val description = stringResource(R.string.instrument_trade_filter, shown)
    Box {
        TextButton({ open = true }, Modifier.testTag("instrument-trade-filter").semantics { contentDescription = description },
            contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text(shown, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
            Spacer(Modifier.width(4.dp))
            ExpansionChevron(open)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text(all) }, { open = false; onChoose(null) }, Modifier.testTag("instrument-trade-filter-all"))
            accounts.forEach { account ->
                DropdownMenuItem({ Text(account.name) }, { open = false; onChoose(account.id) },
                    Modifier.testTag("instrument-trade-filter-${account.id}"))
            }
        }
    }
}

/** "9月3日周四" or "Thu, Sep 3". */
private fun dayLabel(date: LocalDate, locale: java.util.Locale): String =
    date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMdEEE"), locale))

@Composable private fun TradeDay(date: LocalDate, trades: List<Trade>, accounts: Map<Long, SavingsAccount>, onOpen: (Trade) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().testTag("trade-day-$date")) {
        Text(dayLabel(date, locale), Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassCard {
            trades.forEachIndexed { index, trade ->
                if (index > 0) FormDivider()
                TradeRow(trade, accounts[trade.investment_id]?.name.orEmpty()) { onOpen(trade) }
            }
        }
    }
}

/** Buy or sell and the account over its time, units and price; the settled amount over its fee. */
@Composable private fun TradeRow(trade: Trade, account: String, onOpen: () -> Unit) {
    val small = MaterialTheme.typography.bodySmall
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val large = MaterialTheme.typography.bodyLarge
    val time = Instant.ofEpochMilli(trade.occurred_at_ms).atZone(ZoneId.systemDefault()).format(rememberClockFormatter())
    val buy = trade.direction == Direction.BUY
    val dividend = trade.direction == Direction.DIVIDEND
    val settled = tradeAmountWithFee(trade.direction, trade.amount_minor, trade.fee_minor)
    val digits = trade.currency.fraction_digits
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onOpen)
        .testTag("trade-record-${trade.id}").padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val direction = stringResource(when (trade.direction) {
                Direction.BUY -> dev.valnook.core.designsystem.R.string.buy
                Direction.SELL -> dev.valnook.core.designsystem.R.string.sell
                Direction.DIVIDEND -> dev.valnook.core.designsystem.R.string.source_dividend
            })
            // A dividend is income, coloured as a gain.
            val color = profitColor(if (buy || dividend) 1 else -1)
            // One line, read as one: only the direction takes its colour.
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color = color)) { append(direction) }
                if (account.isNotEmpty()) append(" · $account")
            }, style = large, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // Dividends are dated by day only: their stored time just orders them, so it is not shown.
            Text(if (dividend) stringResource(R.string.dividend_row_detail) else
                time + " · " + stringResource(R.string.investment_units, DecimalRules.format_e8(trade.quantity_e8)) +
                " × " + DecimalRules.format_e8(trade.execution_price_e8), style = small, color = muted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text((if (dividend) "+" else "") + DecimalRules.format_display(settled, digits) + " " + trade.currency.code,
                style = large.copy(fontFeatureSettings = "tnum"), maxLines = 1)
            if (!dividend) Text(stringResource(R.string.investment_trade_fee) + " " + DecimalRules.format_display(trade.fee_minor, digits),
                style = small.copy(fontFeatureSettings = "tnum"), color = muted, maxLines = 1)
        }
    }
}

/**
 * Today's price for every account holding it. Saving the same price again still records it as today's update; the
 * dialog stays while saving and closes once saved.
 */
@Composable private fun PriceDialog(editor: PriceEditor, onSave: () -> Unit, onDismiss: () -> Unit) {
    val input by editor.input.collectAsStateWithLifecycle()
    val submission by editor.session.state.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val instrument = editor.instrument
    val working = submission.phase == SubmissionPhase.WORKING
    LaunchedEffect(submission.phase) { if (submission.phase == SubmissionPhase.SUCCEEDED) onDismiss() }
    AnimatedGlassDialog(true, onDismiss) {
        GlassCard(Modifier.testTag("instrument-price-dialog")) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.instrument_price_update_action), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listOf(instrument.name, instrument.symbol, instrument.currency.code).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.instrument_price_shared_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Field(stringResource(R.string.instrument_current_price), input, editor::update, numeric = true,
                    enabled = submission.editable)
                Text(stringResource(R.string.instrument_price_last, shortDate(instrument.priceUpdatedAtMs, locale),
                    priceText(instrument.currentPriceE5) + " " + instrument.currency.code),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ErrorMessage(submission.error?.name)
                if (submission.phase == SubmissionPhase.UNKNOWN) Text(stringResource(R.string.investment_unknown_result),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onDismiss, Modifier.testTag("instrument-price-cancel"), enabled = !working) {
                        Text(stringResource(dev.valnook.core.designsystem.R.string.cancel)) }
                    Button(onSave, Modifier.testTag("instrument-price-save"), enabled = !working) {
                        if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(dev.valnook.core.designsystem.R.string.save))
                    }
                }
            }
        }
    }
}

/** Where to buy into or sell from; the account the page was opened from comes first and is marked. */
@Composable private fun AccountPicker(mode: Picking, accounts: List<SavingsAccount>, holdings: List<InstrumentHolding>,
    contextAccountId: Long?, currency: Currency, onDismiss: () -> Unit, onChoose: (SavingsAccount) -> Unit) {
    val ordered = remember(accounts, contextAccountId) { accounts.sortedByDescending { it.id == contextAccountId } }
    AnimatedGlassDialog(true, onDismiss) {
        GlassCard(Modifier.testTag("instrument-account-picker")) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(stringResource(when (mode) {
                    Picking.BUY -> R.string.instrument_buy_into
                    Picking.SELL -> R.string.instrument_sell_from
                    Picking.DIVIDEND -> R.string.instrument_dividend_to
                }),
                    Modifier.padding(horizontal = 20.dp).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                ordered.forEach { account ->
                    val position = holdings.firstOrNull { it.account.id == account.id }?.position
                    val chosen = account.id == contextAccountId
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onChoose(account) }
                        .semantics(mergeDescendants = true) { selected = chosen }
                        .testTag("instrument-account-pick-${account.id}").padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AccountAvatar(account.icon.symbol, account.icon.imageKey, size = 32.dp)
                        Column(Modifier.weight(1f)) {
                            Text(account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (position != null && position.holding_quantity_e8 > 0) Text(stringResource(R.string.investment_units,
                                DecimalRules.format_e8(position.holding_quantity_e8)), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (chosen) FaIcon(FaIcons.check, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                    }
                }
                TextButton(onDismiss, Modifier.align(Alignment.End).padding(end = 12.dp, top = 4.dp)) {
                    Text(stringResource(dev.valnook.core.designsystem.R.string.cancel)) }
            }
        }
    }
}
