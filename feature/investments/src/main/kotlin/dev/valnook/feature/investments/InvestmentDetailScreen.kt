package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*

@Composable fun InvestmentDetail(vm: InvestmentDetailViewModel, onTrade: (Long) -> Unit,
    onCreateTrade: (Direction, Investment) -> Unit, onCost: (Investment) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val asset = state.asset
    if (asset == null) {
        Text(stringResource(if (!state.loaded) R.string.investment_loading else if (state.failed)
            R.string.investment_read_failed else R.string.investment_record_unavailable))
        return
    }
    val profit = InvestmentProfitCalculator.fromReadModel(asset)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { HoldingColumns() }
        item { HoldingRow(asset) }
        item {
            Text(stringResource(R.string.investment_remaining_cost, money(profit.remainingCost, asset.currency)))
            Text(stringResource(R.string.investment_realized_total, money(profit.realized, asset.currency)))
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton({ onCreateTrade(Direction.BUY, asset) }, Modifier.weight(1f)) { Text(stringResource(R.string.investment_buy)) }
            ActionButton({ onCreateTrade(Direction.SELL, asset) }, Modifier.weight(1f)) { Text(stringResource(R.string.investment_sell)) }
        } }
        if (asset.opening_quantity_e8 > 0) item { ActionButton({ onCost(asset) }) { Text(stringResource(R.string.investment_edit_opening_cost)) } }
        item { Text(stringResource(R.string.investment_trade_history), style = MaterialTheme.typography.titleLarge) }
        if (state.trades.isEmpty()) item { EmptyState(stringResource(R.string.investment_no_trades)) }
        items(state.trades, key = { it.id }) { TradeHistoryItem(it) { onTrade(it.id) } }
        if (state.hasMore) item { TextButton(vm::loadMore) { Text(stringResource(R.string.investment_load_more)) } }
    }
}
