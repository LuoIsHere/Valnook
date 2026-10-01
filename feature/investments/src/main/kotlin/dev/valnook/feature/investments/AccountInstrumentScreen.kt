package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*

@Composable
fun AccountInstrumentScreen(vm: AccountInstrumentViewModel, showPosition: @Composable (Long) -> Unit,
    onBuy: () -> Unit, onOpening: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountInstrumentState.Loading -> CircularProgressIndicator()
        AccountInstrumentState.Failed -> Text("账户投资记录读取失败，请返回重试")
        AccountInstrumentState.Missing -> EmptyState("账户或标的不存在")
        is AccountInstrumentState.Ready -> {
            val position = current.position
            if (position != null) showPosition(position.id)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                item { InstrumentIdentity(current.instrument) }
                item { Text("交易历史", style = MaterialTheme.typography.titleLarge) }
                item { EmptyState("暂无交易记录") }
                item { ActionButton(onBuy, Modifier.fillMaxWidth()) { Text("买入") } }
                item { ActionButton(onOpening, Modifier.fillMaxWidth()) { Text("录入期初持仓") } }
            }
        }
    }
}
