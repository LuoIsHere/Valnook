package dev.valnook.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.feature.accounts.*
import dev.valnook.feature.statistics.*
import dev.valnook.domain.model.StatisticsMetric
import dev.valnook.feature.cash.*
import dev.valnook.feature.deposits.*
import dev.valnook.feature.investments.*

internal fun EntryProviderScope<NavKey>.accountEntries(graph: AppGraph, open: (NavKey) -> Unit, back: () -> Unit,
    onEditSortActionChanged: ((() -> Unit)?) -> Unit, onEditSaveChanged: (AccountEditToolbarState?) -> Unit,
    onGroupsToolbarChanged: (AccountGroupsToolbarState?) -> Unit, onTitleReserve: (NavKey, Int) -> Unit,
    onDeleteCash: (Long, Long) -> Unit) {
    entry<AccountGroupsKey> {
        val vm = pageViewModel { AccountGroupsViewModel(graph.overview, requireNotNull(graph.accountGroupWriter)) }
        AccountGroupsScreen(vm, onGroupsToolbarChanged)
    }
    // A page left off the bar opens from settings as its hidden key: the same page.
    entry<AccountsKey> { AccountsPage(graph, open) }
    entry<HiddenAccountsKey> { AccountsPage(graph, open) }
    entry<AccountEditKey> { route ->
        val vm = pageViewModel { AccountEditViewModel(route.id, graph.overview, graph.commands, createSavedStateHandle()) }
        dev.valnook.app.appearance.AccountPhotoPicker(vm::changeImage) { pickImage ->
            AccountEditScreen(vm, back, onEditSortActionChanged, pickImage, onEditSaveChanged) { cashId ->
                route.id?.let { onDeleteCash(it, cashId) }
            }
        }
    }
    entry<AccountKey> { route ->
        val summaryVm = pageViewModel { AccountsViewModel(graph.overview) }
        val cashVm = pageViewModel { CashViewModel(route.id, graph.cash, graph.cashPages, createSavedStateHandle(), graph.overview, graph.clock) }
        val depositVm = pageViewModel { DepositsViewModel(route.id, false, graph.depositPages, graph.clock) }
        val portfolioVm = pageViewModel { PortfolioViewModel(graph.overview) }
        val trendVm = pageViewModel { AccountTrendViewModel(route.id, graph.statistics) }
        val trend by trendVm.points.collectAsStateWithLifecycle()
        // Today's point and the date range move on while the page is away.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { trendVm.refresh() }
        val initial = if (route.section == "CASH") AccountDetailSection.ACCOUNTS else
            runCatching { AccountDetailSection.valueOf(route.section) }.getOrDefault(AccountDetailSection.ACCOUNTS)
        AccountDetailScreen(summaryVm, route.id, initial,
            cashContent = { slot -> CashScreen(cashVm, slot.expansion, slot.note) { open(CashKey(route.id, it)) } },
            depositContent = {
                DepositsScreen(depositVm, { open(DepositFormKey(route.id, DepositFormMode.CREATE)) },
                    { open(DepositDetailKey(route.id, it)) }, { open(SettledDepositsKey(route.id)) })
            },
            investmentContent = {
                AccountInvestments(portfolioVm, route.id, false, { open(InstrumentKey(it, route.id)) },
                    { open(PositionCreateKey(route.id)) }, { open(AccountInvestmentsKey(route.id, true)) })
            }, trend = trend, onTitleReserve = { onTitleReserve(route, it) })
    }
    entry<AccountInvestmentsKey> { route ->
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        AccountInvestments(vm, route.accountId, route.all, { open(InstrumentKey(it, route.accountId)) },
            { open(PositionCreateKey(route.accountId)) }, { open(route.copy(all = true)) })
    }
}

@Composable private fun AccountsPage(graph: AppGraph, open: (NavKey) -> Unit) {
    val vm = pageViewModel { AccountsViewModel(graph.overview, graph.accountGroupWriter) }
    AccountsScreen(vm, { open(AccountKey(it)) },
        { accountId, cashId -> open(CashKey(accountId, cashId)) },
        { open(AccountKey(it, AccountDetailSection.DEPOSITS.name)) },
        { open(AccountKey(it, AccountDetailSection.INVESTMENTS.name)) }, header = {
            CurrentMonthTrend(pageViewModel { CurrentMonthTrendViewModel(StatisticsMetric.AVAILABLE_CASH, graph.statistics, graph.clock) })
        })
}
