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
@Composable private fun TotalLine(label: String, total: ConvertedTotal, colorByValue: Boolean = false) {
    val valueColor = if (colorByValue && total.complete) profitColor(total.amount.signum())
        else MaterialTheme.colorScheme.onSurfaceVariant
    SummaryMetric(label, converted(total), valueColor = valueColor)
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
        PortfolioState.Loading -> PageLoading()
        PortfolioState.Failed -> PageFailure(stringResource(R.string.investment_load_failed), vm::reload)
        is PortfolioState.Ready -> {
            val groups = current.snapshot.positions.filter { it.holding_quantity_e8 > 0 }.groupBy { it.account_id }
            LazyColumn(Modifier.fillMaxSize().testTag("investment-home"), contentPadding = pageContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                item {
                    GlassCard(Modifier.testTag("investment-summary"), prominent = true) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.investment_total_value), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(converted(current.overview.investmentValue), Modifier.testTag("investment-market-total"),
                            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
                        MetricGrid(2) { index ->
                            Column {
                                if (index == 0) TotalLine(stringResource(R.string.investment_total_unrealized), current.overview.floating, colorByValue = true)
                                else TotalLine(stringResource(R.string.investment_total_realized), current.overview.realized, colorByValue = true)
                            }
                        }
                        var showFx by rememberSaveable { mutableStateOf(false) }
                        TextButton(onClick = { showFx = !showFx }, contentPadding = PaddingValues(horizontal = 0.dp),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("investment-fx-info")) {
                            Text(stringResource(R.string.investment_fx_summary), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AnimatedVisibility(showFx, enter = expandVertically(tween(140)) + fadeIn(tween(90)),
                            exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
                            HintMessage(stringResource(R.string.investment_current_fx_hint))
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
                            if (account.realized.complete) account.realized.amount.signum() else 0,
                            if (account.floating.complete) account.floating.amount.signum() else 0,
                            realizedModifier = Modifier.testTag("account-realized-${account.account.id}"),
                            floatingModifier = Modifier.testTag("account-floating-${account.account.id}"))
                        if (!account.investmentValue.complete || !account.floating.complete || !account.realized.complete)
                            Text(stringResource(R.string.investment_partial_summary), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        AnimatedVisibility(expanded, enter = expandVertically(tween(140)) + fadeIn(tween(90)),
                            exit = shrinkVertically(tween(120)) + fadeOut(tween(80))) {
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
    onPosition: (Long) -> Unit, onAdd: () -> Unit, onAll: () -> Unit) {
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
        item { Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.investment_add_to_account)) } }
    }
}
