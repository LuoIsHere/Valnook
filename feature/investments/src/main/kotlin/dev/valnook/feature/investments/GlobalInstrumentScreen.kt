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
    if (current == null) { Text(if (state == PortfolioState.Failed) "标的读取失败" else "正在读取标的")
        return }
    val instrument = current.snapshot.instruments.firstOrNull { it.id == instrumentId }
    if (instrument == null) { EmptyState("标的不存在")
        return }
    val summary = current.instrumentSummaries.first { it.instrument.id == instrumentId }
    val associated = current.snapshot.positions.filter { it.instrumentId == instrumentId }.associateBy { it.account_id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { InstrumentIdentity(instrument) }
        item {
            Text("跨账户总市值 " + money(summary.marketValue, instrument.currency), style = MaterialTheme.typography.titleMedium)
            AccountProfitRow("已实现盈亏 " + money(summary.realized, instrument.currency),
                "浮动盈亏 " + money(summary.floating, instrument.currency), summary.floating?.signum() ?: 0)
        }
        item { ActionButton(onEdit) { Text("编辑标的与当前价格") } }
        item { Text("按账户分别计算成本", style = MaterialTheme.typography.titleMedium) }
        if (current.snapshot.accounts.isEmpty()) item { EmptyState("请先创建账户") }
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
                if (position == null) Text("尚无持仓与交易记录", style = MaterialTheme.typography.bodySmall)
                else {
                    val profit = InvestmentProfitCalculator.fromReadModel(position)
                    val average = profit.average_cost?.setScale(8, RoundingMode.HALF_UP)
                        ?.stripTrailingZeros()?.toPlainString()?.plus(" " + instrument.currency.code) ?: "待补全"
                    Text(DecimalRules.format_e8(position.holding_quantity_e8) + " 份 · 成本 " +
                        average + " /份", style = MaterialTheme.typography.bodySmall)
                    AccountProfitRow("已实现盈亏 " + money(profit.realized, instrument.currency),
                        "浮动盈亏 " + money(profit.unrealized, instrument.currency), profit.unrealized?.signum() ?: 0)
                }
            }
        }
    }
}
