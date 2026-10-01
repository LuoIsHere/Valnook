package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        Text(if (!state.loaded) "正在读取持仓" else if (state.failed) "读取失败" else "记录不可用")
        return
    }
    val profit = InvestmentProfitCalculator.fromReadModel(asset)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { HoldingColumns() }
        item { HoldingRow(asset) }
        item {
            Text("持仓成本总额 " + money(profit.remainingCost, asset.currency))
            Text("累计已实现盈亏 " + money(profit.realized, asset.currency))
        }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton({ onCreateTrade(Direction.BUY, asset) }, Modifier.weight(1f)) { Text("买入") }
            ActionButton({ onCreateTrade(Direction.SELL, asset) }, Modifier.weight(1f)) { Text("卖出") }
        } }
        if (asset.opening_quantity_e8 > 0) item { ActionButton({ onCost(asset) }) { Text("修改期初成本") } }
        item { Text("交易历史", style = MaterialTheme.typography.titleLarge) }
        if (state.trades.isEmpty()) item { EmptyState("暂无交易记录") }
        items(state.trades, key = { it.id }) { TradeHistoryItem(it) { onTrade(it.id) } }
        if (state.hasMore) item { TextButton(vm::loadMore) { Text("加载更多") } }
    }
}
