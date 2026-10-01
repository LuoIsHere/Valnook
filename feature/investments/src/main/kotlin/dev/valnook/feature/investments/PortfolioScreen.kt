package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

internal fun money(value: BigDecimal?, currency: Currency): String =
    value?.setScale(currency.fraction_digits, RoundingMode.HALF_UP)?.toPlainString()?.plus(" " + currency.code) ?: "待补全"
internal fun converted(total: ConvertedTotal): String =
    total.currency?.let { money(total.amount, it) } ?: "请设置主币种"
@Composable private fun TotalLine(label: String, total: ConvertedTotal) {
    Text(label + " " + converted(total), style = MaterialTheme.typography.titleMedium)
    if (!total.complete) Text("不完整 · " + total.missing.joinToString("、") {
        when (it.kind) {
            MissingKind.BASE_CURRENCY -> "未设置主币种"
            MissingKind.EXCHANGE_RATE -> "缺少 ${it.currencyCode} 汇率"
            MissingKind.CURRENT_COST -> "持仓成本待补全"
            MissingKind.HISTORICAL_COST -> "历史成本待补全"
            MissingKind.INVALID_HISTORY -> "历史记录异常"
        }
    }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
@Composable fun InvestmentHome(vm: PortfolioViewModel, onAccount: (Long) -> Unit,
    onLibrary: () -> Unit, onTypes: () -> Unit, onCreate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        PortfolioState.Loading -> CircularProgressIndicator()
        PortfolioState.Failed -> Text("投资读取失败，请返回后重试")
        is PortfolioState.Ready -> {
            val groups = current.snapshot.positions.filter { it.holding_quantity_e8 > 0 }.groupBy { it.account_id }
            LazyColumn(Modifier.fillMaxSize().testTag("investment-home"), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                item { OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TotalLine("总投资市值", current.overview.investmentValue)
                        TotalLine("总浮动盈亏", current.overview.floating)
                        TotalLine("累计已实现盈亏", current.overview.realized)
                        Text("按当前手动汇率折算，非历史汇兑收益", style = MaterialTheme.typography.bodySmall)
                    }
                } }
                item { ActionButton(onLibrary, Modifier.fillMaxWidth()) { Text("所有投资品") } }
                item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionButton(onCreate, Modifier.weight(1f)) { Text("新增标的") }
                    ActionButton(onTypes, Modifier.weight(1f)) { Text("管理类型") }
                } }
                if (groups.isEmpty()) item { EmptyState("暂无持仓，历史已实现盈亏仍参与汇总") }
                items(current.overview.accounts.filter { it.account.id in groups }, key = { it.account.id }) { account ->
                    var expanded by rememberSaveable(account.account.id) { mutableStateOf(false) }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { expanded = !expanded }) { Text(account.account.name + if (expanded) " ▴" else " ▾") }
                            TotalLine("浮动盈亏", account.floating)
                            TotalLine("已实现盈亏", account.realized)
                            if (expanded) groups[account.account.id].orEmpty().forEach { InvestmentCard(it) }
                            ActionButton({ onAccount(account.account.id) }) { Text("查看持仓与记录") }
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
    if (current == null) { Text(if (state == PortfolioState.Failed) "读取失败" else "正在读取")
        return }
    val positions = current.snapshot.positions.filter { it.account_id == accountId && (all || it.holding_quantity_e8 > 0) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(if (all) "所有投资品" else "当前持仓", style = MaterialTheme.typography.titleLarge) }
        if (!all) item { ActionButton(onAll, Modifier.fillMaxWidth()) { Text("所有投资品") } }
        if (positions.isEmpty()) item { EmptyState(if (all) "尚未关联投资品" else "暂无持仓") }
        items(positions, key = { it.id }) { InvestmentCard(it) { onPosition(it.id) } }
        item { Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) { Text("选择全局标的买入") } }
    }
}
@Composable fun InvestmentCard(asset: Investment, onOpen: (() -> Unit)? = null) {
    val profit = InvestmentProfitCalculator.fromReadModel(asset)
    val gainColor = if ((profit.unrealized?.signum() ?: 0) > 0) androidx.compose.ui.graphics.Color(0xFF168457)
        else if ((profit.unrealized?.signum() ?: 0) < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val compact = LocalDensity.current.fontScale > 1.3f
    val content: @Composable ColumnScope.() -> Unit = {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val name: @Composable () -> Unit = {
                Column {
                    Text(asset.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(asset.symbol, style = MaterialTheme.typography.bodySmall)
                }
            }
            val amount: @Composable () -> Unit = {
                Column {
                    Text(money(AssetValuation.marketValue(asset), asset.currency), color = gainColor,
                        style = MaterialTheme.typography.titleMedium)
                    Text("持有 " + dev.valnook.domain.money.DecimalRules.format_e8(asset.holding_quantity_e8) + " 份",
                        style = MaterialTheme.typography.bodySmall)
                    Text("浮动盈亏 " + money(profit.unrealized, asset.currency), color = gainColor,
                        style = MaterialTheme.typography.bodySmall)
                    Text("持仓均价 " + (profit.average_cost?.setScale(8, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "—") +
                        " " + asset.currency.code + "／份", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (compact || maxWidth < 340.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { name()
                amount() }
            else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { name() }
                Box(Modifier.weight(1.3f)) { amount() }
            }
        }
    }
    if (onOpen == null) OutlinedCard(Modifier.fillMaxWidth(), content = content)
    else OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth(), content = content)
}
@Composable fun InstrumentLibrary(vm: PortfolioViewModel, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? PortfolioState.Ready
    if (current == null) { Text(if (state == PortfolioState.Failed) "标的库读取失败，请返回后重试" else "正在读取标的库")
        return }
    var query by rememberSaveable { mutableStateOf("") }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Field("搜索名称或代码", query, { query = it }) }
        items(current.instrumentSummaries.filter { it.instrument.name.contains(query, true) || it.instrument.symbol.contains(query, true) },
            key = { it.instrument.id }) { summary ->
            val instrument = summary.instrument
            OutlinedCard(onClick = { onOpen(instrument.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(instrument.name, style = MaterialTheme.typography.titleLarge)
                    Text(instrument.symbol + " · " + instrument.typeName)
                    Text("当前价 " + BigDecimal.valueOf(instrument.currentPriceE5, 5).stripTrailingZeros().toPlainString() + " " + instrument.currency.code)
                    Text("浮动盈亏 " + money(summary.floating, instrument.currency))
                    Text("累计已实现盈亏 " + money(summary.realized, instrument.currency))
                }
            }
        }
    }
}
@Composable fun GlobalInstrumentDetail(vm: PortfolioViewModel, instrumentId: Long,
    onEdit: () -> Unit, onPosition: (Long, Long) -> Unit, onBuy: (Long) -> Unit, onOpening: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? PortfolioState.Ready ?: return
    val instrument = current.snapshot.instruments.firstOrNull { it.id == instrumentId } ?: return
    val summary = current.instrumentSummaries.first { it.instrument.id == instrumentId }
    val associated = current.snapshot.positions.filter { it.instrumentId == instrumentId }.associateBy { it.account_id }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(instrument.name + " · " + instrument.symbol, style = MaterialTheme.typography.headlineSmall) }
        item { Text(instrument.typeName + " · " + instrument.currency.code) }
        item {
            Text("跨账户总市值 " + money(summary.marketValue, instrument.currency))
            Text("跨账户浮动盈亏 " + money(summary.floating, instrument.currency))
            Text("跨账户累计已实现盈亏 " + money(summary.realized, instrument.currency))
        }
        item { ActionButton(onEdit, Modifier.fillMaxWidth()) { Text("编辑标的与当前价格") } }
        item { Text("按账户分别计算成本", style = MaterialTheme.typography.titleMedium) }
        items(current.snapshot.accounts, key = { it.id }) { account ->
            val position = associated[account.id]
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(account.name, style = MaterialTheme.typography.titleLarge)
                    position?.let {
                        InvestmentCard(it) { onPosition(account.id, it.id) }
                        val profit = InvestmentProfitCalculator.fromReadModel(it)
                        Text("累计已实现盈亏 " + money(profit.realized, instrument.currency))
                    }
                    ActionButton({ onBuy(account.id) }) { Text("买入") }
                    if (position == null) ActionButton({ onOpening(account.id) }) { Text("录入期初持仓") }
                }
            }
        }
    }
}
