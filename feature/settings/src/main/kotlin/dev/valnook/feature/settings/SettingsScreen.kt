package dev.valnook.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.Currency

@Composable fun SettingsScreen(vm: SettingsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("主币种与手动汇率", style = MaterialTheme.typography.titleLarge) }
        if (!state.loaded) item {
            Text(state.error ?: "正在读取")
            TextButton(vm::reload) { Text("重试") }
        } else {
            item { CurrencyChoice(state.baseCurrency?.code.orEmpty(), { vm.selectBase(Currency.of(it)) },
                !state.busy, Currency.supported.map { it.code to it.name }) }
            item { Text(if (state.baseCurrency == null) "未设置主币种，原币功能可正常使用" else
                "1 原币 = r " + state.baseCurrency!!.code + "；同币种按 1:1") }
            itemsIndexed(state.rows, key = { index, _ -> index }) { index, row ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CurrencyChoice(row.sourceCurrency.code, { vm.updateRow(index, source = Currency.of(it)) },
                            !state.busy, Currency.supported.map { it.code to it.name },
                            state.rows.filterIndexed { i, _ -> i != index }.map { it.sourceCurrency.code }.toSet() +
                                listOfNotNull(state.baseCurrency?.code))
                        Field("汇率（最多12位小数）", row.rateInput, { vm.updateRow(index, rate = it) }, true, !state.busy)
                        TextButton({ vm.removeRate(index) }, enabled = !state.busy) { Text("删除此汇率") }
                    }
                }
            }
            item { ActionButton(vm::addRate, enabled = !state.busy && state.baseCurrency != null) { Text("＋ 添加汇率") } }
            item { Text("按当前手动汇率折算，非历史汇兑收益。更换主币种后，请补充匹配的新币种对。") }
            item {
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.saved) Text("设置已保存")
                Button(vm::save, enabled = !state.busy && state.baseCurrency != null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.busy) "保存中" else "保存设置")
                }
            }
        }
    }
}
