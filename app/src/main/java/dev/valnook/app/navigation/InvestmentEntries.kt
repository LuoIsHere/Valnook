package dev.valnook.app.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.feature.investments.*

internal fun EntryProviderScope<NavKey>.investmentEntries(graph: AppGraph, open: (NavKey) -> Unit, back: () -> Unit, accountName: (Long) -> String) {
    entry<InvestmentsKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        InvestmentHome(vm, { open(AccountInvestmentsKey(it)) }) { accountId, positionId ->
            open(AssetKey(accountId, positionId))
        }
    }
    entry<HiddenInvestmentsKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        InvestmentHome(vm, { open(AccountInvestmentsKey(it)) }) { accountId, positionId ->
            open(AssetKey(accountId, positionId))
        }
    }
    entry<InstrumentLibraryKey> {
        val vm = pageViewModel { InstrumentLibraryViewModel(graph.overview, graph.investments) }
        InstrumentLibrary(vm, { open(InstrumentKey(it)) }, { open(TypesKey) }, { open(InstrumentEditKey()) })
    }
    entry<InstrumentKey> { route ->
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        GlobalInstrumentDetail(vm, route.id, { open(InstrumentEditKey(route.id)) },
            { open(AccountInstrumentKey(it, route.id)) })
    }
    entry<AccountInstrumentKey> { route ->
        val vm = pageViewModel { AccountInstrumentViewModel(route.accountId, route.instrumentId, graph.overview) }
        AccountInstrumentScreen(vm,
            { positionId -> AccountPositionDetail(graph, route.accountId, positionId, open) },
            { open(TradeFormKey(route.accountId, TradeFormMode.CREATE, instrumentId = route.instrumentId)) })
    }
    entry<PositionCreateKey> { route ->
        val vm = pageViewModel { PositionCreateViewModel(graph.instruments, createSavedStateHandle()) }
        PositionCreateForm(vm, { open(AccountInstrumentKey(route.accountId, it)) }, { open(InstrumentLibraryKey) })
    }
    entry<InstrumentEditKey> { route ->
        val vm = pageViewModel { InstrumentEditViewModel(route.id, graph.instruments, graph.investments, graph.commands, createSavedStateHandle()) }
        InstrumentEditScreen(vm, back)
    }
    entry<InstrumentPriceEditKey> { route ->
        val vm = pageViewModel { InstrumentEditViewModel(route.id, graph.instruments, graph.investments, graph.commands, createSavedStateHandle()) }
        InstrumentPriceEditScreen(vm, back)
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
        AccountPositionDetail(graph, route.account_id, route.id, open)
    }
    entry<TradeDetailKey> { route ->
        val vm = pageViewModel { TradeDetailViewModel(route.account_id, route.trade_id, graph.investments) }
        TradeDetailScreen(vm, accountName(route.account_id), { _, trade -> open(TradeFormKey(route.account_id, TradeFormMode.EDIT, tradeId = trade.id)) },
            { _, trade -> open(TradeFormKey(route.account_id, TradeFormMode.DELETE, tradeId = trade.id)) })
    }
    entry<TradeFormKey> { route ->
        val vm = pageViewModel { TradeFormViewModel(route.accountId, route.mode, route.instrumentId, route.positionId,
            route.tradeId, route.direction, graph.investments, graph.instruments, graph.cash,
            graph.commands, graph.clock, createSavedStateHandle()) }
        TradeForm(vm, back)
    }
}

@Composable
private fun AccountPositionDetail(graph: AppGraph, accountId: Long, positionId: Long, open: (NavKey) -> Unit) {
    val vm = pageViewModel { InvestmentDetailViewModel(accountId, positionId, graph.investments) }
    InvestmentDetail(vm, { open(TradeDetailKey(accountId, it)) },
        { direction, asset -> open(TradeFormKey(accountId, TradeFormMode.CREATE, asset.instrumentId, asset.id, direction = direction)) },
        { open(InstrumentPriceEditKey(it.instrumentId)) })
}
