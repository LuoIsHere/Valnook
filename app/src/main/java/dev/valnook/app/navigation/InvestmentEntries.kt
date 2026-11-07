package dev.valnook.app.navigation

import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.feature.investments.*
import dev.valnook.feature.statistics.*
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.StatisticsMetric

internal fun EntryProviderScope<NavKey>.investmentEntries(graph: AppGraph, open: (NavKey) -> Unit, back: () -> Unit,
    accountName: (Long) -> String, onTitleReserve: (NavKey, Int) -> Unit) {
    entry<InvestmentsKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        InvestmentHome(vm, { open(AccountInvestmentsKey(it)) }, trend = {
            CurrentMonthTrend(pageViewModel { CurrentMonthTrendViewModel(StatisticsMetric.INVESTMENT_VALUE, graph.statistics, graph.clock) })
        }) { accountId, instrumentId ->
            open(InstrumentKey(instrumentId, accountId))
        }
    }
    entry<HiddenInvestmentsKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        InvestmentHome(vm, { open(AccountInvestmentsKey(it)) }, trend = {
            CurrentMonthTrend(pageViewModel { CurrentMonthTrendViewModel(StatisticsMetric.INVESTMENT_VALUE, graph.statistics, graph.clock) })
        }) { accountId, instrumentId ->
            open(InstrumentKey(instrumentId, accountId))
        }
    }
    entry<InstrumentLibraryKey> {
        val vm = pageViewModel { InstrumentLibraryViewModel(graph.overview, graph.investments) }
        InstrumentLibrary(vm, { open(InstrumentKey(it)) }, { open(TypesKey) }, { open(InstrumentEditKey()) })
    }
    entry<InstrumentKey> { route ->
        val vm = pageViewModel { InstrumentDetailViewModel(route.id, graph.overview, graph.investments, graph.instruments,
            createSavedStateHandle(), graph.clock) }
        InstrumentDetail(vm, route.accountId, { onTitleReserve(route, it) },
            onTrade = { accountId, tradeId -> open(TradeDetailKey(accountId, tradeId)) },
            onBuy = { accountId, positionId -> open(TradeFormKey(accountId, TradeFormMode.CREATE, route.id, positionId,
                direction = Direction.BUY)) },
            onSell = { accountId, positionId -> open(TradeFormKey(accountId, TradeFormMode.CREATE, route.id, positionId,
                direction = Direction.SELL)) },
            onPrice = { open(InstrumentPriceEditKey(route.id)) })
    }
    entry<PositionCreateKey> { route ->
        val vm = pageViewModel { PositionCreateViewModel(graph.instruments, createSavedStateHandle()) }
        PositionCreateForm(vm, { open(InstrumentKey(it, route.accountId)) }, { open(InstrumentLibraryKey) })
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
