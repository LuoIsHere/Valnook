package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.command.SubmissionPhase
import java.math.RoundingMode

private fun totalText(total: ConvertedTotal): String = total.currency?.let {
    total.amount.setScale(it.fraction_digits, RoundingMode.HALF_UP).toPlainString() + " " + it.code
} ?: "请设置主币种"

private fun missingText(total: ConvertedTotal): String = total.missing.map {
    when (it.kind) {
        MissingKind.BASE_CURRENCY -> "未设置主币种"
        MissingKind.EXCHANGE_RATE -> "缺少 ${it.currencyCode} 汇率"
        MissingKind.CURRENT_COST -> "当前成本待补全"
        MissingKind.HISTORICAL_COST -> "历史成本待补全"
        MissingKind.INVALID_HISTORY -> "历史记录异常"
    }
}.distinct().joinToString("、")

@Composable fun AccountsScreen(vm: AccountsViewModel, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountsState.Loading -> CircularProgressIndicator()
        AccountsState.Failed -> Text("账户读取失败，请返回后重试")
        is AccountsState.Ready -> AccountsContent(current.overview, onOpen)
    }
}
@Composable fun AccountsContent(overview: AssetOverview, onOpen: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("accounts-list"), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("总资产", style = MaterialTheme.typography.titleMedium)
                    Text(totalText(overview.total), style = MaterialTheme.typography.headlineMedium)
                    Text("可用现金 " + totalText(overview.cash), style = MaterialTheme.typography.bodyMedium)
                    if (!overview.total.complete) Text("汇总不完整 · " + missingText(overview.total), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (overview.accounts.isEmpty()) item { EmptyState("点击右上角＋新增账户") }
        items(overview.accounts, key = { it.account.id }) { row ->
            OutlinedCard(onClick = { onOpen(row.account.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val name: @Composable () -> Unit = {
                            Text(row.account.name, style = MaterialTheme.typography.titleLarge,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        val amount: @Composable () -> Unit = {
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(totalText(row.total), style = MaterialTheme.typography.titleMedium)
                                Text("可用现金 " + totalText(row.cash), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (maxWidth < 300.dp || LocalDensity.current.fontScale > 1.3f) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                name()
                                amount()
                            }
                        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.weight(1f)) { name() }
                            Box(Modifier.weight(1.3f)) { amount() }
                        }
                    }
                    if (!row.total.complete) Text("部分汇总 · " + missingText(row.total), color = MaterialTheme.colorScheme.error)
                    if (row.account.note.isNotEmpty()) Text(row.account.note, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
@Composable fun AccountEditScreen(vm: AccountEditViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val submission by vm.submission.collectAsStateWithLifecycle()
    LaunchedEffect(submission.phase) { if (vm.consumeSuccess()) onBack() }
    if (!state.loaded) {
        if (state.loadError) TextButton(onClick = vm::reload) { Text("读取失败，重试") } else CircularProgressIndicator()
        return
    }
    FormLayout("编辑账户", submission.phase == SubmissionPhase.WORKING,
        submission.phase != SubmissionPhase.SUCCEEDED, vm::submit,
        if (submission.phase == SubmissionPhase.UNKNOWN) "核对并重试" else "保存") {
        Field("名称", state.name, vm::changeName, enabled = submission.editable)
        Field("备注", state.note, vm::changeNote, enabled = submission.editable)
        Text("现金余额", style = MaterialTheme.typography.titleMedium)
        state.rows.forEach { row ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f)) {
                        CurrencyChoice(row.currency.code, { vm.changeRow(row.key, currency = Currency.of(it)) },
                            submission.editable && !row.locked, Currency.supported.map { it.code to it.name },
                            state.rows.filter { it.key != row.key }.map { it.currency.code }.toSet())
                    }
                    Box(Modifier.weight(1.3f)) { Field("余额", row.balanceInput,
                        { vm.changeRow(row.key, balance = it) }, true, submission.editable) }
                }
                if (row.locked) Text("币种已锁定：该币种余额已成功保存或发生现金联动", style = MaterialTheme.typography.bodySmall)
                else TextButton(onClick = { vm.removeRow(row.key) }, enabled = submission.editable) { Text("取消此行") }
            }
        }
        ActionButton(vm::addRow, enabled = submission.editable) { Text("＋ 添加币种") }
        ErrorMessage(submission.error?.name)
        if (submission.phase == SubmissionPhase.UNKNOWN) Text("保存结果待核对，使用原操作重试")
    }
}
