package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.ConvertedTotal
import dev.valnook.domain.model.Investment
import dev.valnook.domain.money.DecimalRules

/**
 * One account's investments as one card of rows, like its cash and deposits: what it holds now, with its profit
 * over them above, or with [all] the positions it has closed.
 */
@Composable fun AccountInvestments(vm: PortfolioViewModel, accountId: Long, all: Boolean,
    onInstrument: (Long) -> Unit, onAdd: () -> Unit, onAll: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? PortfolioState.Ready
    if (current == null) { Text(stringResource(if (state == PortfolioState.Failed) R.string.investment_read_failed else R.string.investment_loading))
        return }
    val positions = current.snapshot.positions.filter { it.account_id == accountId && (if (all) it.isClosed else it.holding_quantity_e8 > 0) }
    val account = current.overview.accounts.firstOrNull { it.account.id == accountId }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize().testTag("account-investments"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!all && account != null) item(key = "profit") {
            ProfitSummary(account.floating, account.realized)
        }
        // A little more room above the title than below, so it reads with the holdings under it.
        if (!all) item(key = "header") {
            SectionHeader(stringResource(R.string.investment_current_holdings), stringResource(R.string.investment_closed_positions),
                onAll, Modifier.padding(top = 4.dp).testTag("account-investments-header"),
                Modifier.testTag("account-investments-closed"))
        }
        if (all && positions.isEmpty()) item(key = "empty") { EmptyState(stringResource(R.string.investment_no_closed_positions)) }
        if (!all || positions.isNotEmpty()) item(key = "card") {
            GlassCard(Modifier.testTag("account-investments-card")) {
                positions.forEachIndexed { index, position ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                    if (all) ClosedPositionRow(position) { onInstrument(position.instrumentId) }
                    else PositionRow(position) { onInstrument(position.instrumentId) }
                }
                if (!all) {
                    if (positions.isEmpty()) Text(stringResource(R.string.investment_no_holdings), Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium, color = muted)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("account-investments-add")
                        .clickable(role = Role.Button, onClick = onAdd).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.investment_add_to_account), style = MaterialTheme.typography.bodyLarge, color = muted)
                    }
                }
            }
        }
        if (!all) item(key = "fees") {
            Text(stringResource(R.string.investment_cost_includes_fees), Modifier.padding(start = 4.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
    }
}

/** The account's floating and realized profit in the base currency, as the investments page shows them. */
@Composable private fun ProfitSummary(floating: ConvertedTotal, realized: ConvertedTotal, modifier: Modifier = Modifier) {
    GlassCard(modifier.testTag("account-investments-profit")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfitValue(stringResource(R.string.investment_total_unrealized), floating, Modifier.weight(1f).testTag("account-investments-floating"))
            ProfitValue(stringResource(R.string.investment_total_realized), realized, Modifier.weight(1f).testTag("account-investments-realized"))
        }
    }
}

@Composable private fun ProfitValue(label: String, total: ConvertedTotal, modifier: Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val sign = total.currency?.takeIf { total.complete }?.let { displayedSign(total.amount, it.fraction_digits) } ?: 0
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        val sum = total.currency?.let { (if (sign > 0) "+" else "") + money(total.amount, it) } ?: converted(total)
        // Wraps rather than shrinking: a shrunk line could be cut off by fonts taller than the default.
        Text(sum, style = style.copy(fontFeatureSettings = "tnum"),
            color = if (sign != 0) profitColor(sign) else MaterialTheme.colorScheme.onSurface)
        if (!total.complete) Text(stringResource(R.string.investment_partial_summary), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error)
    }
}

/** Name in full with its code under it, so long fund names are not cut short. */
@Composable private fun PositionName(position: Investment) {
    Text(position.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    InstrumentCodeLine(position.symbol, position.currency.code)
}

/** What it is worth and has made so far, then how many it holds at what price and cost. */
@Composable private fun PositionRow(position: Investment, onOpen: () -> Unit) {
    val currency = position.currency
    val profit = InvestmentProfitCalculator.fromReadModel(position)
    val color = profitColor(displayedSign(profit.unrealized, currency.fraction_digits))
    val small = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum")
    Column(Modifier.testTag("holding-${position.id}").fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PositionName(position)
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val style = MaterialTheme.typography.titleLarge
            Text(buildAnnotatedString {
                append(DecimalRules.format_display(AssetValuation.marketValue(position), currency.fraction_digits))
                append(" ")
                withStyle(SpanStyle(fontSize = 12.sp, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(currency.code)
                }
            }, Modifier.weight(1f).testTag("holding-value-${position.id}"), style = style.copy(fontFeatureSettings = "tnum"),
                maxLines = 1, autoSize = TextAutoSize.StepBased(10.sp, style.fontSize, .5.sp))
            Text(signed(profit.unrealized, currency), Modifier.testTag("holding-profit-${position.id}"),
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = color, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.investment_holding_detail, DecimalRules.format_e8(position.holding_quantity_e8),
                DecimalRules.format_e8(position.current_price_e8), averageText(profit.average_cost)),
                Modifier.weight(1f), style = small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(profit.unrealizedPercent?.let { (if (displayedSign(it, 2) > 0) "+" else "") +
                it.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%" } ?: "—",
                style = small, color = color, maxLines = 1, textAlign = TextAlign.End)
        }
    }
}

/** A closed position keeps only what it realized. */
@Composable private fun ClosedPositionRow(position: Investment, onOpen: () -> Unit) {
    val currency = position.currency
    val realized = InvestmentProfitCalculator.fromReadModel(position).realized
    Column(Modifier.testTag("holding-${position.id}").fillMaxWidth().clickable(onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PositionName(position)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusTag(stringResource(R.string.instrument_closed_position), TagTone.NEUTRAL)
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.investment_realized_label), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(signed(realized, currency, withCode = true), style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                color = profitColor(displayedSign(realized, currency.fraction_digits)), maxLines = 1)
        }
    }
}
