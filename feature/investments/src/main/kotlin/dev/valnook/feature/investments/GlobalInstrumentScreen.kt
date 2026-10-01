package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.*
import dev.valnook.domain.money.DecimalRules
import java.math.RoundingMode

/** Global metadata and account summaries. Financial actions live one level deeper. */
@Composable
fun GlobalInstrumentDetail(vm: PortfolioViewModel, instrumentId: Long, onEdit: () -> Unit, onAccount: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? PortfolioState.Ready
    if (current == null) { Text(stringResource(if (state == PortfolioState.Failed) R.string.instrument_load_failed else R.string.instrument_loading))
        return }
    val instrument = current.snapshot.instruments.firstOrNull { it.id == instrumentId }
    if (instrument == null) { EmptyState(stringResource(R.string.instrument_not_found))
        return }
    val summary = current.instrumentSummaries.first { it.instrument.id == instrumentId }
    val associated = current.snapshot.positions.filter { it.instrumentId == instrumentId }.associateBy { it.account_id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { InstrumentIdentity(instrument) }
        item {
            Text(stringResource(R.string.instrument_cross_account_value, money(summary.marketValue, instrument.currency)), style = MaterialTheme.typography.titleMedium)
            AccountProfitRow(stringResource(R.string.investment_realized_value, money(summary.realized, instrument.currency)),
                stringResource(R.string.investment_unrealized_value, money(summary.floating, instrument.currency)),
                summary.realized?.signum() ?: 0, summary.floating?.signum() ?: 0)
        }
        item { ActionButton(onEdit) { Text(stringResource(R.string.instrument_edit_action)) } }
        item { Text(stringResource(R.string.instrument_cost_by_account), style = MaterialTheme.typography.titleMedium) }
        if (current.snapshot.accounts.isEmpty()) item { EmptyState(stringResource(R.string.instrument_create_account_first)) }
        items(current.snapshot.accounts, key = { it.id }) { account ->
            val position = associated[account.id]
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onAccount(account.id) }
                .padding(vertical = 6.dp).testTag("instrument-account-${account.id}"),
                verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    position?.let {
                        Text(money(AssetValuation.marketValue(it), instrument.currency), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End)
                    }
                }
                if (position == null) Text(stringResource(R.string.instrument_no_account_history), style = MaterialTheme.typography.bodySmall)
                else {
                    val profit = InvestmentProfitCalculator.fromReadModel(position)
                    val average = profit.average_cost?.setScale(8, RoundingMode.HALF_UP)
                        ?.stripTrailingZeros()?.toPlainString()?.plus(" " + instrument.currency.code) ?: "—"
                    Text(stringResource(R.string.instrument_position_cost,
                        DecimalRules.format_e8(position.holding_quantity_e8), average), style = MaterialTheme.typography.bodySmall)
                    AccountProfitRow(stringResource(R.string.investment_realized_value, money(profit.realized, instrument.currency)),
                        stringResource(R.string.investment_unrealized_value, money(profit.unrealized, instrument.currency)),
                        profit.realized?.signum() ?: 0, profit.unrealized?.signum() ?: 0)
                }
            }
        }
    }
}
