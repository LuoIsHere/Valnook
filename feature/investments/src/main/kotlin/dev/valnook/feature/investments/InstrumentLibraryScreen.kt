package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import java.math.BigDecimal

@Composable
fun InstrumentLibrary(vm: InstrumentLibraryViewModel, onOpen: (Long) -> Unit, onTypes: () -> Unit, onCreate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? InstrumentLibraryState.Ready
    if (current == null) {
        Text(if (state == InstrumentLibraryState.Failed) "标的库读取失败，请返回后重试" else "正在读取标的库")
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val matches = current.instruments.filter { it.instrument.name.contains(query, true) || it.instrument.symbol.contains(query, true) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(onCreate, Modifier.weight(1f), enabled = current.hasTypes) { Text("新增标的") }
            ActionButton(onTypes, Modifier.weight(1f)) { Text("资产类型管理") }
        } }
        if (!current.hasTypes) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text("请先创建资产类型，再新增标的")
                ActionButton(onTypes) { Text("创建资产类型") }
            }
        }
        item { Field("搜索名称或代码", query, { query = it }) }
        if (matches.isEmpty()) item { EmptyState(if (query.isBlank()) "尚无投资标的" else "没有匹配的标的") }
        items(matches, key = { it.instrument.id }) { summary ->
            val instrument = summary.instrument
            Column(Modifier.fillMaxWidth().clickable { onOpen(instrument.id) }.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                InstrumentIdentity(instrument)
                Text("当前价 " + BigDecimal.valueOf(instrument.currentPriceE5, 5).stripTrailingZeros().toPlainString() + " " + instrument.currency.code,
                    style = MaterialTheme.typography.bodySmall)
                AccountProfitRow("已实现盈亏 " + money(summary.realized, instrument.currency),
                    "浮动盈亏 " + money(summary.floating, instrument.currency), summary.floating?.signum() ?: 0)
            }
        }
    }
}
