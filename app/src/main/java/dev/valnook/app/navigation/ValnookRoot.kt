package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onSizeChanged
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
import dev.valnook.designsystem.*
import dev.valnook.domain.model.AppSettings
import dev.valnook.feature.settings.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ValnookRoot(graph: AppGraph, onExit: () -> Unit = {}) {
    // Three finite root entries retain scroll/form state; all temporary entries are popped normally.
    val stack = rememberNavBackStack(InvestmentsKey, SettingsKey, AccountsKey)
    val current = stack.last()
    val accounts by remember(graph) { graph.accounts.observe_accounts() }.collectAsStateWithLifecycle(emptyList())
    val settings by remember(graph) { graph.settings.observeSettings() }.collectAsStateWithLifecycle(AppSettings())
    val pickerRates = remember(settings) {
        val base = settings.baseCurrency?.code
        CurrencyPickerRates(base, settings.rates.filter { it.targetCurrency.code == base }
            .associate { it.sourceCurrency.code to it.rate.stripTrailingZeros().toPlainString() })
    }
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
        is AccountInstrumentKey -> accountName(current.accountId)
        else -> "Valnook"
    }
    val offset = with(LocalDensity.current) { 16.dp.roundToPx() }
    val barHeight = (64f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.ime.getBottom(density) > 0
    val showCapsule = current.isRoot() && !keyboardOpen
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val systemBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    val bottomSpace = if (showCapsule) with(density) { overlayHeightPx.toDp() } else systemBottom
    Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal), topBar = {
            TopAppBar(title = { Text(title, maxLines = 2) }, expandedHeight = barHeight, navigationIcon = {
                if (!current.isRoot()) TopBarAction("返回", back)
            }, actions = {
                when (current) {
                    AccountsKey -> IconButton({ open(AccountEditKey()) }, Modifier.semantics { contentDescription = "新增账户" }) { Text("＋") }
                    InvestmentsKey -> IconButton({ open(InstrumentLibraryKey) }, Modifier.semantics { contentDescription = "所有投资品" }) { MenuIcon() }
                    is AccountKey -> TopBarAction("编辑", { open(AccountEditKey(current.id)) })
                    else -> Unit
                }
            })
        }) { padding ->
            // Only top/horizontal insets shrink the viewport. Bottom space belongs inside lists/forms.
            CompositionLocalProvider(LocalPageBottomSpace provides bottomSpace, LocalCurrencyPickerRates provides pickerRates) {
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).testTag("page-viewport")) {
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
        if (showCapsule) FloatingNavigationBar(current, selectRoot,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { overlayHeightPx = it.height }
                .testTag("floating-navigation-overlay"))
    }
}
