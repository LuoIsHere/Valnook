package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.feature.accounts.*
import dev.valnook.feature.cash.*
import dev.valnook.feature.deposits.*
import dev.valnook.feature.investments.*

internal fun EntryProviderScope<NavKey>.accountEntries(graph: AppGraph, open: (NavKey) -> Unit, back: () -> Unit) {
    entry<AccountsKey> {
        val vm = pageViewModel { AccountsViewModel(graph.overview) }
        AccountsScreen(vm) { open(AccountKey(it)) }
    }
    entry<AccountEditKey> { route ->
        val vm = pageViewModel { AccountEditViewModel(route.id, graph.overview, graph.commands, createSavedStateHandle()) }
        AccountEditScreen(vm, back)
    }
    entry<AccountKey> { route ->
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val holder = rememberSaveableStateHolder()
        Column {
            PrimaryTabRow(selectedTabIndex = tab) {
                listOf("现金", "定期", "投资").forEachIndexed { index, title ->
                    Tab(tab == index, { tab = index }, text = { Text(title) })
                }
            }
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> {
                        val vm = pageViewModel { CashViewModel(route.id, graph.cash, graph.cashPages, createSavedStateHandle()) }
                        CashScreen(vm, { open(CashKey(route.id, it)) }) { open(AccountEditKey(route.id)) }
                    }
                    1 -> {
                        val vm = pageViewModel { DepositsViewModel(route.id, false, graph.depositPages, graph.clock) }
                        DepositsScreen(vm, { open(DepositFormKey(route.id, DepositFormMode.CREATE)) },
                            { open(DepositDetailKey(route.id, it)) }, { open(SettledDepositsKey(route.id)) })
                    }
                    2 -> {
                        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
                        AccountInvestments(vm, route.id, false, { open(AssetKey(route.id, it)) },
                            { open(TradeFormKey(route.id, TradeFormMode.CREATE)) }, { open(AccountInvestmentsKey(route.id, true)) })
                    }
                }
            }
        }
    }
    entry<AccountInvestmentsKey> { route ->
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        AccountInvestments(vm, route.accountId, route.all, { open(AssetKey(route.accountId, it)) },
            { open(TradeFormKey(route.accountId, TradeFormMode.CREATE)) }, { open(route.copy(all = true)) })
    }
}
