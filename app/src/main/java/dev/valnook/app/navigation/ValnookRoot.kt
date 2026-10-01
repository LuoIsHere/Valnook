package dev.valnook.app.navigation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import dev.valnook.app.di.AppGraph
import dev.valnook.designsystem.TopBarAction
import dev.valnook.feature.settings.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ValnookRoot(graph: AppGraph, onExit: () -> Unit = {}) {
    // Three finite root entries retain scroll/form state; all temporary entries are popped normally.
    val stack = rememberNavBackStack(InvestmentsKey, SettingsKey, AccountsKey)
    val current = stack.last()
    val accounts by remember(graph) { graph.accounts.observe_accounts() }.collectAsStateWithLifecycle(emptyList())
    val accountName: (Long) -> String = { id -> accounts.firstOrNull { it.id == id }?.name.orEmpty() }
    val open: (NavKey) -> Unit = { key -> if (stack.last() != key) stack.add(key) }
    val selectRoot: (NavKey) -> Unit = { key ->
        if (current.isRoot() && current != key) { stack.remove(key)
            stack.add(key) }
    }
    val back: () -> Unit = {
        if (!stack.last().isRoot()) stack.removeAt(stack.lastIndex)
        else if (stack.last() != AccountsKey) { stack.remove(AccountsKey)
            stack.add(AccountsKey) }
        else onExit()
    }
    val title = when (current) {
        AccountsKey -> "账户"
        InvestmentsKey -> "投资"
        SettingsKey -> "设置"
        is AccountKey -> accountName(current.id)
        is AccountEditKey -> "编辑账户"
        is InstrumentLibraryKey, is AccountInvestmentsKey -> "所有投资品"
        is InstrumentKey -> "标的详情"
        else -> "Valnook"
    }
    val offset = with(LocalDensity.current) { 16.dp.roundToPx() }
    val barHeight = (64f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 2) }, expandedHeight = barHeight, navigationIcon = {
            if (!current.isRoot()) TopBarAction("返回", back)
        }, actions = {
            when (current) {
                AccountsKey -> IconButton({ open(AccountEditKey()) }, Modifier.semantics { contentDescription = "新增账户" }) { Text("＋") }
                is AccountKey -> TopBarAction("编辑", { open(AccountEditKey(current.id)) })
                else -> Unit
            }
        })
    }, bottomBar = {
        if (current.isRoot()) Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth().testTag("root-capsule")) {
                Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(AccountsKey to "账户", InvestmentsKey to "投资", SettingsKey to "设置").forEach { (key, label) ->
                        val selected = current == key
                        TextButton({ selectRoot(key) }, Modifier.weight(1f).heightIn(min = 48.dp).semantics { this.selected = selected },
                            shape = RoundedCornerShape(50), colors = ButtonDefaults.textButtonColors(
                                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                            Text(label)
                        }
                    }
                }
            }
        }
    }) { padding ->
        // Scaffold supplies measured capsule height plus system inset, exactly once.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            NavDisplay(backStack = stack, onBack = back, sizeTransform = null,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(), rememberViewModelStoreNavEntryDecorator()),
                transitionSpec = { NavigationMotion.forward(offset) }, popTransitionSpec = { NavigationMotion.back(offset) },
                predictivePopTransitionSpec = { NavigationMotion.no_preview() }, entryProvider = entryProvider {
                    accountEntries(graph, open, back)
                    investmentEntries(graph, open, back, accountName)
                    ledgerEntries(graph, open, back, accountName)
                    entry<SettingsKey> {
                        val vm = pageViewModel { SettingsViewModel(graph.settings, createSavedStateHandle()) }
                        SettingsScreen(vm)
                    }
                })
        }
    }
}
