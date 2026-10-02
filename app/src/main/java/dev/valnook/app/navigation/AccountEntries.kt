package dev.valnook.app.navigation

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
        AccountsScreen(vm, { open(AccountKey(it)) },
            { accountId, cashId -> open(CashKey(accountId, cashId)) },
            { open(AccountKey(it, AccountDetailSection.DEPOSITS.name)) },
            { open(AccountKey(it, AccountDetailSection.INVESTMENTS.name)) })
    }
    entry<AccountEditKey> { route ->
        val vm = pageViewModel { AccountEditViewModel(route.id, graph.overview, graph.commands, createSavedStateHandle()) }
        AccountEditScreen(vm, back)
    }
    entry<AccountKey> { route ->
        val summaryVm = pageViewModel { AccountsViewModel(graph.overview) }
        val cashVm = pageViewModel { CashViewModel(route.id, graph.cash, graph.cashPages, createSavedStateHandle()) }
        val depositVm = pageViewModel { DepositsViewModel(route.id, false, graph.depositPages, graph.clock) }
        val portfolioVm = pageViewModel { PortfolioViewModel(graph.overview) }
        val initial = runCatching { AccountDetailSection.valueOf(route.section) }.getOrDefault(AccountDetailSection.CASH)
        AccountDetailScreen(summaryVm, route.id, initial,
            cashContent = { CashScreen(cashVm) { open(CashKey(route.id, it)) } },
            depositContent = {
                DepositsScreen(depositVm, { open(DepositFormKey(route.id, DepositFormMode.CREATE)) },
                    { open(DepositDetailKey(route.id, it)) }, { open(SettledDepositsKey(route.id)) })
            },
            investmentContent = {
                AccountInvestments(portfolioVm, route.id, false, { open(AssetKey(route.id, it)) },
                    { open(TradeFormKey(route.id, TradeFormMode.CREATE)) }, { open(AccountInvestmentsKey(route.id, true)) })
            })
    }
    entry<AccountInvestmentsKey> { route ->
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        AccountInvestments(vm, route.accountId, route.all, { open(AssetKey(route.accountId, it)) },
            { open(TradeFormKey(route.accountId, TradeFormMode.CREATE)) }, { open(route.copy(all = true)) })
    }
}
