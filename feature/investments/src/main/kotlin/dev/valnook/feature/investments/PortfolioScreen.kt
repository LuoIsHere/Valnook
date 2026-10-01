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
@Composable private fun TotalLine(label: String, total: ConvertedTotal, prominent: Boolean = false) {
    if (prominent) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(converted(total), Modifier.testTag("investment-market-total"),
            style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
    } else Text(label + " " + converted(total), style = MaterialTheme.typography.titleMedium)
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
@Composable fun InvestmentHome(vm: PortfolioViewModel, onAccount: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        PortfolioState.Loading -> CircularProgressIndicator()
        PortfolioState.Failed -> Text("投资读取失败，请返回后重试")
        is PortfolioState.Ready -> {
            val groups = current.snapshot.positions.filter { it.holding_quantity_e8 > 0 }.groupBy { it.account_id }
            LazyColumn(Modifier.fillMaxSize().testTag("investment-home"), contentPadding = pageContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        TotalLine("总投资市值", current.overview.investmentValue, prominent = true)
                        TotalLine("总浮动盈亏", current.overview.floating)
                        TotalLine("累计已实现盈亏", current.overview.realized)
                        Text("按当前手动汇率折算，非历史汇兑收益", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (groups.isEmpty()) item { EmptyState("暂无持仓，历史已实现盈亏仍参与汇总") }
                items(current.overview.accounts.filter { it.account.id in groups }, key = { it.account.id }) { account ->
                    var expanded by rememberSaveable(account.account.id) { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(account.account.name + if (expanded) " ▴" else " ▾",
                                Modifier.weight(1f).clickable { expanded = !expanded },
                                style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(converted(account.investmentValue), Modifier.weight(1.3f)
                                .clickable(role = androidx.compose.ui.semantics.Role.Button) { onAccount(account.account.id) }
                                .semantics { contentDescription = "查看 ${account.account.name} 持仓与记录" }
                                .testTag("investment-account-total-${account.account.id}"),
                                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        }
                        AccountProfitRow("已实现盈亏 " + converted(account.realized),
                            "浮动盈亏 " + converted(account.floating), account.floating.amount.signum(),
                            realizedModifier = Modifier.testTag("account-realized-${account.account.id}"),
                            floatingModifier = Modifier.testTag("account-floating-${account.account.id}"))
                        if (!account.investmentValue.complete || !account.floating.complete || !account.realized.complete)
                            Text("部分汇总 · 请检查主币种或成本记录", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        if (expanded) {
                            HoldingColumns()
                            groups[account.account.id].orEmpty().forEach { HoldingRow(it) }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Text(if (all) "所有投资品" else "当前持仓", style = MaterialTheme.typography.titleLarge) }
        if (!all) item { ActionButton(onAll, Modifier.fillMaxWidth()) { Text("所有投资品") } }
        if (positions.isEmpty()) item { EmptyState(if (all) "尚未关联投资品" else "暂无持仓") }
        item { HoldingColumns() }
        items(positions, key = { it.id }) { HoldingRow(it) { onPosition(it.id) } }
        item { Button(onClick = onBuy, modifier = Modifier.fillMaxWidth()) { Text("选择全局标的买入") } }
    }
}
