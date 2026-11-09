package dev.valnook.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation3.runtime.*
import dev.valnook.app.di.AppGraph
import dev.valnook.feature.investments.*
import dev.valnook.feature.statistics.*
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.StatisticsMetric
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Accounts chosen on [InvestmentAccountPickKey], waiting for the form that asked: the account and the instrument's
 * position there, by the form's request. Held for the session; a form takes its answer when it is shown again.
 */
internal class AccountPicks { val chosen = mutableStateMapOf<String, Pair<Long, Long?>>() }

/**
 * For a new record of [instrumentId], opens the account choice and hands the answer to [onPicked]; null, with no
 * choice offered, otherwise.
 */
@Composable private fun accountChooser(instrumentId: Long?, direction: Direction, accountId: Long, picks: AccountPicks,
    open: (NavKey) -> Unit, onPicked: (accountId: Long, positionId: Long?) -> Unit): (() -> Unit)? {
    if (instrumentId == null) return null
    val request = rememberSaveable { UUID.randomUUID().toString() }
    val picked = picks.chosen[request]
    LaunchedEffect(picked) {
        if (picked != null) { picks.chosen.remove(request); onPicked(picked.first, picked.second) }
    }
    return { open(InvestmentAccountPickKey(instrumentId, direction, accountId, request)) }
}

internal fun EntryProviderScope<NavKey>.investmentEntries(graph: AppGraph, open: (NavKey) -> Unit, back: () -> Unit,
    accountName: (Long) -> String, onTitleReserve: (NavKey, Int) -> Unit, picks: AccountPicks,
    onTypeToolbar: (TypeEditToolbarState?) -> Unit = {}, onInstrumentToolbar: (InstrumentEditToolbarState?) -> Unit = {},
    onDividendToolbar: (DividendToolbarState?) -> Unit = {}) {
    // A page left off the bar opens from settings as its hidden key: the same page.
    entry<InvestmentsKey> { InvestmentsPage(graph, open) }
    entry<HiddenInvestmentsKey> { InvestmentsPage(graph, open) }
    entry<RealizedProfitKey> {
        val vm = pageViewModel { PortfolioViewModel(graph.overview) }
        RealizedProfitScreen(vm) { open(InstrumentKey(it)) }
    }
    entry<InstrumentLibraryKey> {
        val vm = pageViewModel { InstrumentLibraryViewModel(graph.overview, graph.investments) }
        InstrumentLibrary(vm, { open(InstrumentKey(it)) })
    }
    entry<InstrumentKey> { route ->
        val vm = pageViewModel { InstrumentDetailViewModel(route.id, graph.overview, graph.investments, graph.instruments,
            createSavedStateHandle(), graph.clock, graph.commands) }
        InstrumentDetail(vm, route.accountId, { onTitleReserve(route, it) },
            onTrade = { accountId, tradeId -> open(TradeDetailKey(accountId, tradeId)) },
            onBuy = { accountId, positionId -> open(TradeFormKey(accountId, TradeFormMode.CREATE, route.id, positionId,
                direction = Direction.BUY)) },
            onSell = { accountId, positionId -> open(TradeFormKey(accountId, TradeFormMode.CREATE, route.id, positionId,
                direction = Direction.SELL)) },
            onDividend = { accountId, positionId -> open(DividendFormKey(accountId, positionId, instrumentId = route.id)) })
    }
    entry<InvestmentAccountPickKey> { route ->
        val vm = pageViewModel { InstrumentDetailViewModel(route.instrumentId, graph.overview, graph.investments, graph.instruments,
            createSavedStateHandle(), graph.clock, graph.commands) }
        InstrumentAccountPicker(vm, route.direction, route.selectedAccountId) { accountId, positionId ->
            picks.chosen[route.request] = accountId to positionId
            back()
        }
    }
    entry<PositionCreateKey> { route ->
        val vm = pageViewModel { PositionCreateViewModel(graph.instruments, createSavedStateHandle()) }
        PositionCreateForm(vm, { open(InstrumentKey(it, route.accountId)) }, { open(InstrumentLibraryKey) })
    }
    entry<InstrumentEditKey> { route ->
        val vm = pageViewModel { InstrumentEditViewModel(route.id, graph.instruments, graph.investments, graph.commands, createSavedStateHandle()) }
        InstrumentEditScreen(vm, back, onInstrumentToolbar)
    }
    entry<TypesKey> {
        val vm = pageViewModel { AssetTypesViewModel(graph.investments,
            graph.instruments.observeInstruments().map { rows -> rows.map { it.typeId } }) }
        TypesScreen(vm) { open(TypeEditKey(it.id, it.name)) }
    }
    entry<TypeEditKey> { route ->
        val vm = pageViewModel { TypeEditViewModel(route.id, route.name, graph.commands, createSavedStateHandle(),
            graph.instruments.observeInstruments().map { rows -> rows.count { it.typeId == route.id } }) }
        TypeEditScreen(vm, back, onTypeToolbar)
    }
    entry<TradeDetailKey> { route ->
        val vm = pageViewModel { TradeDetailViewModel(route.account_id, route.trade_id, graph.investments) }
        TradeDetailScreen(vm, accountName(route.account_id), { _, trade ->
            // A dividend is edited in its own form; buys and sells in the trade form.
            if (trade.direction == Direction.DIVIDEND) open(DividendFormKey(route.account_id, tradeId = trade.id))
            else open(TradeFormKey(route.account_id, TradeFormMode.EDIT, tradeId = trade.id)) },
            { _, trade -> open(TradeFormKey(route.account_id, TradeFormMode.DELETE, tradeId = trade.id)) })
    }
    entry<TradeFormKey> { route ->
        val vm = pageViewModel { TradeFormViewModel(route.accountId, route.mode, route.instrumentId, route.positionId,
            route.tradeId, route.direction, graph.investments, graph.instruments, graph.cash,
            graph.commands, graph.clock, createSavedStateHandle()) }
        val accountId by vm.accountId.collectAsStateWithLifecycle()
        val choose = accountChooser(route.instrumentId?.takeIf { route.mode == TradeFormMode.CREATE }, route.direction,
            accountId, picks, open) { id, positionId -> vm.selectAccount(id, positionId) }
        TradeForm(vm, back, accountName(accountId), choose)
    }
    entry<DividendFormKey> { route ->
        val vm = pageViewModel { DividendFormViewModel(route.accountId, route.positionId, route.tradeId, graph.investments,
            graph.instruments, graph.cash, graph.commands, graph.clock, createSavedStateHandle()) }
        val accountId by vm.accountId.collectAsStateWithLifecycle()
        val choose = accountChooser(route.instrumentId?.takeIf { route.tradeId == null }, Direction.DIVIDEND, accountId, picks,
            open) { id, positionId -> positionId?.let { vm.selectAccount(id, it) } }
        DividendForm(vm, accountName(accountId), back, onDividendToolbar, choose)
    }
}

@Composable private fun InvestmentsPage(graph: AppGraph, open: (NavKey) -> Unit) {
    val vm = pageViewModel { PortfolioViewModel(graph.overview) }
    InvestmentHome(vm, { open(AccountInvestmentsKey(it)) }, trend = {
        CurrentMonthTrend(pageViewModel { CurrentMonthTrendViewModel(StatisticsMetric.INVESTMENT_VALUE, graph.statistics, graph.clock) })
    }, onRealized = { open(RealizedProfitKey) }) { accountId, instrumentId ->
        open(InstrumentKey(instrumentId, accountId))
    }
}
