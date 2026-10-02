package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

internal fun money(value: BigDecimal?, currency: Currency): String =
    value?.setScale(currency.fraction_digits, RoundingMode.HALF_UP)?.toPlainString()?.plus(" " + currency.code) ?: "—"
@Composable internal fun converted(total: ConvertedTotal): String =
    total.currency?.let { money(total.amount, it) } ?: stringResource(R.string.investment_set_base)
@Composable private fun TotalLine(label: String, total: ConvertedTotal, prominent: Boolean = false,
    colorByValue: Boolean = false) {
    val valueColor = if (colorByValue && total.complete) profitColor(total.amount.signum())
        else MaterialTheme.colorScheme.onSurface
    if (prominent) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(converted(total), Modifier.testTag("investment-market-total"),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
    } else Text(label + " " + converted(total), style = MaterialTheme.typography.titleMedium, color = valueColor)
    val missing = total.missing.mapNotNull {
        when (it.kind) {
            MissingKind.BASE_CURRENCY -> stringResource(R.string.investment_missing_base)
            MissingKind.EXCHANGE_RATE -> null
            MissingKind.CURRENT_COST -> stringResource(R.string.investment_missing_current_cost)
            MissingKind.HISTORICAL_COST -> stringResource(R.string.investment_missing_historical_cost)
            MissingKind.INVALID_HISTORY -> stringResource(R.string.investment_invalid_history)
        }
    }.distinct().joinToString(" · ")
    if (!total.complete && missing.isNotEmpty()) Text(stringResource(R.string.investment_incomplete, missing),
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
@Composable fun InvestmentHome(vm: PortfolioViewModel, onAccount: (Long) -> Unit,
    onPosition: (Long, Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        PortfolioState.Loading -> CircularProgressIndicator()
        PortfolioState.Failed -> Text(stringResource(R.string.investment_load_failed))
        is PortfolioState.Ready -> {
            val groups = current.snapshot.positions.filter { it.holding_quantity_e8 > 0 }.groupBy { it.account_id }
            LazyColumn(Modifier.fillMaxSize().testTag("investment-home"), contentPadding = pageContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        TotalLine(stringResource(R.string.investment_total_value), current.overview.investmentValue, prominent = true)
                        TotalLine(stringResource(R.string.investment_total_unrealized), current.overview.floating, colorByValue = true)
                        TotalLine(stringResource(R.string.investment_total_realized), current.overview.realized, colorByValue = true)
                        Text(stringResource(R.string.investment_current_fx_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (groups.isEmpty()) item { EmptyState(stringResource(R.string.investment_no_holdings_summary)) }
                items(current.overview.accounts.filter { it.account.id in groups }, key = { it.account.id }) { account ->
                    var expanded by rememberSaveable(account.account.id) { mutableStateOf(false) }
                    val openDescription = stringResource(R.string.investment_open_account, account.account.name)
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(account.account.name + if (expanded) " ▴" else " ▾",
                                Modifier.weight(1f).clickable { expanded = !expanded },
                                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(converted(account.investmentValue), Modifier.weight(1.3f)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onAccount(account.account.id) }
                                .semantics { contentDescription = openDescription }
                                .testTag("investment-account-total-${account.account.id}"),
                                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        AccountProfitRow(stringResource(R.string.investment_realized_value, converted(account.realized)),
                            stringResource(R.string.investment_unrealized_value, converted(account.floating)),
                            if (account.realized.complete) account.realized.amount.signum() else 0,
                            if (account.floating.complete) account.floating.amount.signum() else 0,
                            realizedModifier = Modifier.testTag("account-realized-${account.account.id}"),
                            floatingModifier = Modifier.testTag("account-floating-${account.account.id}"))
                        if (!account.investmentValue.complete || !account.floating.complete || !account.realized.complete)
                            Text(stringResource(R.string.investment_partial_summary), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        if (expanded) {
                            HoldingTable(groups[account.account.id].orEmpty()) {
                                onPosition(account.account.id, it.id)
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable fun AccountInvestments(vm: PortfolioViewModel, accountId: Long, all: Boolean,
    onPosition: (Long) -> Unit, onBuy: () -> Unit, onAll: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? PortfolioState.Ready
    if (current == null) { Text(stringResource(if (state == PortfolioState.Failed) R.string.investment_read_failed else R.string.investment_loading))
        return }
    val positions = current.snapshot.positions.filter { it.account_id == accountId && (all || it.holding_quantity_e8 > 0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Text(stringResource(if (all) R.string.investment_all_instruments else R.string.investment_current_holdings), style = MaterialTheme.typography.titleLarge) }
        if (!all) item { ActionButton(onAll, Modifier.fillMaxWidth()) { Text(stringResource(R.string.investment_all_instruments)) } }
        if (positions.isEmpty()) item { EmptyState(stringResource(if (all) R.string.investment_no_associations else R.string.investment_no_holdings)) }
        item { HoldingTable(positions) { onPosition(it.id) } }
        item { Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.investment_choose_to_buy)) } }
    }
}
