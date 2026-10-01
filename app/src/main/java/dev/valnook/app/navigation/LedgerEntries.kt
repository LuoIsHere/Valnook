package dev.valnook.app.navigation

import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.domain.model.CashSource
import dev.valnook.feature.cash.*
import dev.valnook.feature.deposits.*
import dev.valnook.feature.investments.TradeFormMode

internal fun EntryProviderScope<NavKey>.ledgerEntries(graph: AppGraph, open: (NavKey) -> Unit,
    back: () -> Unit, accountName: (Long) -> String) {
    entry<CashKey> { route ->
        val vm = pageViewModel { CashViewModel(route.account_id, graph.cash, graph.cashPages, createSavedStateHandle()) }
        CashDetail(vm, route.currency, { open(AccountEditKey(route.account_id)) }, { open(CashEntryKey(route.account_id, it)) })
    }
    entry<CashEntryKey> { route ->
        val vm = pageViewModel { CashEntryViewModel(route.account_id, route.entry_id, graph.cash) }
        CashEntryDetailScreen(vm, accountName(route.account_id), { entry ->
            if (entry.editable) open(CashEntryEditKey(route.account_id, entry.id))
            else entry.source_id?.let { sourceId ->
                when (entry.source) {
                    CashSource.TRADE -> open(TradeFormKey(route.account_id, TradeFormMode.EDIT, tradeId = sourceId))
                    CashSource.TERM_OPEN, CashSource.TERM_CLOSE -> open(DepositFormKey(route.account_id, DepositFormMode.EDIT, sourceId))
                    CashSource.CASH_SET -> Unit
                }
            }
        })
    }
    entry<CashEntryEditKey> { route ->
        val vm = pageViewModel { CashEntryEditViewModel(route.accountId, route.entryId, graph.cash, graph.commands, graph.clock, createSavedStateHandle()) }
        CashEntryEditScreen(vm, back)
    }
    entry<SettledDepositsKey> { route ->
        val vm = pageViewModel { DepositsViewModel(route.accountId, true, graph.depositPages, graph.clock) }
        DepositsScreen(vm, {}, { open(DepositDetailKey(route.accountId, it)) }, closed = true)
    }
    entry<DepositDetailKey> { route ->
        val vm = pageViewModel { DepositDetailViewModel(route.account_id, route.deposit_id, graph.deposits, graph.clock) }
        DepositDetailScreen(vm, accountName(route.account_id),
            { open(DepositFormKey(route.account_id, DepositFormMode.EDIT, it.id)) },
            { open(DepositFormKey(route.account_id, DepositFormMode.CLOSE, it.id)) })
    }
    entry<DepositFormKey> { route ->
        val vm = pageViewModel { DepositFormViewModel(route.accountId, route.mode, route.id, graph.deposits,
            graph.commands, graph.clock, createSavedStateHandle()) }
        DepositForm(vm, back)
    }
}
