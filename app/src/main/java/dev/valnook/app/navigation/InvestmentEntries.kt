package dev.valnook.app.navigation

import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.feature.investments.*

internal fun EntryProviderScope<NavKey>.investmentEntries(graph: AppGraph, open: (NavKey) -> Unit, back: () -> Unit, accountName: (Long) -> String) {
    entry<InvestmentsKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        InvestmentHome(vm, { open(AccountInvestmentsKey(it)) }, { open(InstrumentLibraryKey) },
            { open(TypesKey) }, { open(InstrumentEditKey()) })
    }
    entry<InstrumentLibraryKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        InstrumentLibrary(vm) { open(InstrumentKey(it)) }
    }
    entry<InstrumentKey> { route ->
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        GlobalInstrumentDetail(vm, route.id, { open(InstrumentEditKey(route.id)) },
            { accountId, id -> open(AssetKey(accountId, id)) },
            { open(TradeFormKey(it, TradeFormMode.CREATE, instrumentId = route.id)) },
            { open(TradeFormKey(it, TradeFormMode.OPENING, instrumentId = route.id)) })
    }
    entry<InstrumentEditKey> { route ->
        val vm = pageViewModel { InstrumentEditViewModel(route.id, graph.instruments, graph.investments, graph.commands, createSavedStateHandle()) }
        InstrumentEditScreen(vm, back)
    }
    entry<TypesKey> {
        val vm = pageViewModel { AssetTypesViewModel(graph.investments) }
        TypesScreen(vm, { open(TypeEditKey()) }, { open(TypeEditKey(it.id, it.name)) })
    }
    entry<TypeEditKey> { route ->
        val vm = pageViewModel { TypeEditViewModel(route.id, route.name, graph.commands, createSavedStateHandle()) }
        TypeEditScreen(vm, back)
    }
    entry<AssetKey> { route ->
        val vm = pageViewModel { InvestmentDetailViewModel(route.account_id, route.id, graph.investments) }
        InvestmentDetail(vm, { open(TradeDetailKey(route.account_id, it)) },
            { direction, asset -> open(TradeFormKey(route.account_id, TradeFormMode.CREATE, asset.instrumentId, asset.id, direction = direction)) },
            { open(TradeFormKey(route.account_id, TradeFormMode.OPENING_COST, it.instrumentId, it.id)) })
    }
    entry<TradeDetailKey> { route ->
        val vm = pageViewModel { TradeDetailViewModel(route.account_id, route.trade_id, graph.investments) }
        TradeDetailScreen(vm, accountName(route.account_id), { _, trade -> open(TradeFormKey(route.account_id, TradeFormMode.EDIT, tradeId = trade.id)) },
            { _, trade -> open(TradeFormKey(route.account_id, TradeFormMode.DELETE, tradeId = trade.id)) })
    }
    entry<TradeFormKey> { route ->
        val vm = pageViewModel { TradeFormViewModel(route.accountId, route.mode, route.instrumentId, route.positionId,
            route.tradeId, route.direction, graph.investments, graph.instruments, graph.commands, graph.clock, createSavedStateHandle()) }
        TradeForm(vm, back)
    }
}
