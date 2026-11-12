package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import java.math.BigDecimal
import dev.valnook.domain.money.DecimalRules

internal fun money(value: BigDecimal?, currency: Currency): String =
    value?.let { DecimalRules.format_display(it, currency.fraction_digits) + " " + currency.code } ?: "—"
@Composable internal fun converted(total: ConvertedTotal): String =
    total.currency?.let { money(total.amount, it) } ?: stringResource(R.string.investment_set_base)
/** Converted totals follow their displayed rounding; an unknown base currency stays neutral. */
private fun displayedSign(total: ConvertedTotal): Int =
    total.currency?.let { displayedSign(total.amount, it.fraction_digits) } ?: 0
/**
 * A summary figure and, when part of it is missing, why. With [onOpen] the whole figure opens its details, a small
 * chevron after the label saying so.
 */
@Composable private fun TotalLine(label: String, total: ConvertedTotal, modifier: Modifier = Modifier,
    colorByValue: Boolean = false, onOpen: (() -> Unit)? = null, openDescription: String = "") {
    val valueColor = if (colorByValue && total.complete) profitColor(displayedSign(total))
        else MaterialTheme.colorScheme.onSurfaceVariant
    // Both figures of a row take the 48dp the one that opens needs, so the reasons under them stay level.
    if (onOpen == null) SummaryMetric(label, converted(total), modifier.heightIn(min = 48.dp), valueColor = valueColor)
    // The rounded press area reaches past the text by PressOutset on each side and pads it back by the same amount, so
    // the text stays level with the figure beside it and the corners never cut into its first letter.
    else SummaryMetric(label, converted(total), modifier.fillMaxWidth().heightIn(min = 48.dp).outsetHorizontally(PressOutset)
        .clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = openDescription, onClick = onOpen)
        .padding(horizontal = PressOutset), valueColor = valueColor) {
        FaIcon(FaIcons.chevronRight, null, Modifier.size(10.dp), MaterialTheme.colorScheme.onSurfaceVariant)
    }
    IncompleteReason(total)
}

private val PressOutset = 8.dp

/** Lays the content out [amount] wider on each side than its slot, centred on it, without moving its neighbours. */
private fun Modifier.outsetHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Why a total is incomplete, in red under it; currencies without any rate are named together in one reason. */
@Composable internal fun IncompleteReason(total: ConvertedTotal) {
    val rates = total.missing.filter { it.kind == MissingKind.EXCHANGE_RATE }.mapNotNull { it.currencyCode }.distinct().sorted()
    val rateText = if (rates.isEmpty()) null else stringResource(R.string.investment_missing_rate, rates.joinToString(", "))
    val missing = (total.missing.mapNotNull {
        when (it.kind) {
            MissingKind.BASE_CURRENCY -> stringResource(R.string.investment_missing_base)
            MissingKind.EXCHANGE_RATE -> null
            MissingKind.CURRENT_COST -> stringResource(R.string.investment_missing_current_cost)
            MissingKind.HISTORICAL_COST -> stringResource(R.string.investment_missing_historical_cost)
            MissingKind.INVALID_HISTORY -> stringResource(R.string.investment_invalid_history)
        }
    } + listOfNotNull(rateText)).distinct().joinToString(" · ")
    if (!total.complete && missing.isNotEmpty()) Text(stringResource(R.string.investment_incomplete, missing),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
@Composable fun InvestmentHome(vm: PortfolioViewModel, onAccount: (Long) -> Unit,
    trend: @Composable () -> Unit = {}, onRealized: () -> Unit = {},
    onInstrument: (accountId: Long, instrumentId: Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        PortfolioState.Loading -> PageLoading()
        PortfolioState.Failed -> PageFailure(stringResource(R.string.investment_load_failed), vm::reload)
        is PortfolioState.Ready -> {
            val groups = current.snapshot.positions.filter { it.holding_quantity_e8 > 0 }.groupBy { it.account_id }
            LazyColumn(Modifier.fillMaxSize().testTag("investment-home"), contentPadding = pageContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                item("month-trend") {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        trend()
                        MetricGrid(2) { index ->
                            Column {
                                if (index == 0) TotalLine(stringResource(R.string.investment_total_unrealized), current.overview.floating, colorByValue = true)
                                else TotalLine(stringResource(R.string.investment_total_realized), current.overview.realized,
                                    colorByValue = true, onOpen = onRealized, openDescription = stringResource(R.string.realized_open),
                                    modifier = Modifier.testTag("investment-realized-open"))
                            }
                        }
                    }
                }
                if (groups.isEmpty()) item { EmptyState(stringResource(R.string.investment_no_holdings_summary)) }
                items(current.overview.accounts.filter { it.account.id in groups }, key = { it.account.id }) { account ->
                    var expanded by rememberSaveable(account.account.id) { mutableStateOf(false) }
                    val openDescription = stringResource(R.string.investment_open_account, account.account.name)
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val name: @Composable () -> Unit = {
                                val expansion = stringResource(if (expanded) dev.valnook.core.designsystem.R.string.state_expanded
                                    else dev.valnook.core.designsystem.R.string.state_collapsed)
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                    .testTag("investment-account-toggle-${account.account.id}")
                                    .clickable(role = Role.Button) { expanded = !expanded }
                                    .semantics { stateDescription = expansion }, verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    AccountAvatar(account.account.icon.symbol, account.account.icon.imageKey, size = 32.dp)
                                    AccountName(account.account.name, MaterialTheme.typography.titleMedium,
                                        Modifier.weight(1f, fill = false))
                                    ExpansionChevron(expanded)
                                }
                            }
                            val amount: @Composable () -> Unit = {
                            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { onAccount(account.account.id) }
                                .semantics { contentDescription = openDescription }
                                .testTag("investment-account-total-${account.account.id}"),
                                contentAlignment = Alignment.CenterEnd) {
                            Text(converted(account.investmentValue),
                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End)
                            }
                            }
                            if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                                Column { name(); amount() }
                            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1.3f)) { name() }
                                Box(Modifier.weight(1f)) { amount() }
                            }
                        }
                        AccountProfitRow(converted(account.realized),
                            converted(account.floating),
                            if (account.realized.complete) displayedSign(account.realized) else 0,
                            if (account.floating.complete) displayedSign(account.floating) else 0,
                            realizedModifier = Modifier.testTag("account-realized-${account.account.id}"),
                            floatingModifier = Modifier.testTag("account-floating-${account.account.id}"))
                        if (!account.investmentValue.complete || !account.floating.complete || !account.realized.complete)
                            Text(stringResource(R.string.investment_partial_summary), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        AnimatedVisibility(expanded, enter = expandVertically(tween(140)) + fadeIn(tween(90)),
                            exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
                            HoldingTable(groups[account.account.id].orEmpty()) {
                                onInstrument(account.account.id, it.instrumentId)
                            }
                        }
                    }
                }
            }
        }
    }
}
