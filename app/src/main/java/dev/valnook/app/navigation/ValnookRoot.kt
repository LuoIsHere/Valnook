package dev.valnook.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.*
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import dev.valnook.app.di.AppGraph
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.feature.accounts.*
import dev.valnook.feature.cash.*
import dev.valnook.feature.deposits.*
import dev.valnook.feature.investments.*
import dev.valnook.domain.model.InvestmentSection

@Serializable data object AccountsKey:NavKey
@Serializable data class AccountKey(val id:Long):NavKey
@Serializable data class AssetKey(val account_id:Long,val id:Long):NavKey
@Serializable data class CashKey(val account_id:Long,val currency:String):NavKey
@Serializable data class CashEntryKey(val account_id:Long,val entry_id:Long):NavKey
@Serializable data class TradeDetailKey(val account_id:Long,val trade_id:Long):NavKey
@Serializable data class DepositDetailKey(val account_id:Long,val deposit_id:Long):NavKey
@Serializable data class TradeEditKey(val account_id:Long,val trade_id:Long):NavKey
@Serializable data class DepositEditKey(val account_id:Long,val deposit_id:Long):NavKey
@Serializable data class PortfolioKey(val account_id:Long,val section:String):NavKey
@Serializable data class FormKey(val area:String,val account_id:Long):NavKey

@Composable fun ValnookRoot(graph:AppGraph) {
    val owner=requireNotNull(LocalViewModelStoreOwner.current)
    val accounts_vm:AccountsViewModel=viewModel(viewModelStoreOwner=owner,key="accounts",factory=viewModelFactory {
        initializer { AccountsViewModel(graph.accounts,createSavedStateHandle()) } })
    val accounts by accounts_vm.accounts.collectAsStateWithLifecycle()
    val stack=rememberNavBackStack(AccountsKey)
    val current=stack.last()
    val account_id=when(current){is AccountKey->current.id;is AssetKey->current.account_id;is CashKey->current.account_id;is CashEntryKey->current.account_id;is TradeDetailKey->current.account_id;is DepositDetailKey->current.account_id;is TradeEditKey->current.account_id;is DepositEditKey->current.account_id;is PortfolioKey->current.account_id;is FormKey->current.account_id;else->0L}
    val account=accounts.firstOrNull{it.id==account_id}
    val back:()->Unit={if(stack.size>1)stack.removeAt(stack.lastIndex)}
    val transition_offset=with(LocalDensity.current){16.dp.roundToPx()}
    PageScaffold(account?.name ?: stringResource(R.string.app_name),current!=AccountsKey,back,
        if(current is AccountKey&&account!=null) ({accounts_vm.begin(account);stack.add(FormKey("accounts",account_id))}) else null) {
        NavDisplay(backStack=stack,onBack=back,sizeTransform=null,
            transitionSpec={NavigationMotion.forward(transition_offset)},
            popTransitionSpec={NavigationMotion.back(transition_offset)},
            predictivePopTransitionSpec={NavigationMotion.no_preview()},entryProvider=entryProvider {
            entry<AccountsKey> {AccountsScreen(accounts_vm,{stack.add(AccountKey(it))},{stack.add(FormKey("accounts",0))})}
            entry<AccountKey> { route ->
                val cash_vm:CashViewModel=viewModel(viewModelStoreOwner=owner,key="cash-${route.id}",factory=viewModelFactory {
                    initializer{CashViewModel(route.id,graph.cash,graph.commands,createSavedStateHandle(),graph.clock)}})
                val deposit_vm:DepositsViewModel=viewModel(viewModelStoreOwner=owner,key="deposit-${route.id}",factory=viewModelFactory {
                    initializer{DepositsViewModel(route.id,graph.deposits,graph.commands,graph.clock,createSavedStateHandle())}})
                val investment_vm:InvestmentsViewModel=viewModel(viewModelStoreOwner=owner,key="investment-${route.id}",factory=viewModelFactory {
                    initializer{InvestmentsViewModel(route.id,graph.investments,graph.commands,graph.clock,createSavedStateHandle())}})
                var tab by rememberSaveable {mutableIntStateOf(0)}
                val holder=rememberSaveableStateHolder()
                Column {
                    PrimaryTabRow(selectedTabIndex=tab) {
                        listOf(R.string.cash,R.string.deposits,R.string.investments).forEachIndexed {index,label->
                            Tab(selected=tab==index,onClick={tab=index},text={Text(stringResource(label))})
                        }
                    }
                    holder.SaveableStateProvider(tab) {
                        when(tab) {
                            0->CashScreen(cash_vm,{stack.add(CashKey(route.id,it))}){stack.add(FormKey("cash",route.id))}
                            1->DepositsScreen(deposit_vm,{stack.add(FormKey("deposits",route.id))},
                                {stack.add(DepositDetailKey(route.id,it))},{stack.add(PortfolioKey(route.id,"SETTLED"))})
                            else->InvestmentsScreen(investment_vm,{stack.add(AssetKey(route.id,it))},{stack.add(FormKey("investments",route.id))},
                                {stack.add(PortfolioKey(route.id,it.name))})
                        }
                    }
                }
            }
            entry<PortfolioKey> {route->
                if(route.section=="SETTLED") {
                    val vm:DepositsViewModel=viewModel(viewModelStoreOwner=owner,key="deposit-${route.account_id}",factory=viewModelFactory {
                        initializer{DepositsViewModel(route.account_id,graph.deposits,graph.commands,graph.clock,createSavedStateHandle())}})
                    DepositsScreen(vm,{stack.add(FormKey("deposits",route.account_id))},{stack.add(DepositDetailKey(route.account_id,it))},closed=true)
                } else {
                    val vm:InvestmentsViewModel=viewModel(viewModelStoreOwner=owner,key="investment-${route.account_id}",factory=viewModelFactory {
                        initializer{InvestmentsViewModel(route.account_id,graph.investments,graph.commands,graph.clock,createSavedStateHandle())}})
                    InvestmentsScreen(vm,{stack.add(AssetKey(route.account_id,it))},{stack.add(FormKey("investments",route.account_id))},
                        section=InvestmentSection.valueOf(route.section))
                }
            }
            entry<AssetKey> {route->
                val vm:InvestmentsViewModel=viewModel(viewModelStoreOwner=owner,key="investment-${route.account_id}",factory=viewModelFactory {
                    initializer{InvestmentsViewModel(route.account_id,graph.investments,graph.commands,graph.clock,createSavedStateHandle())}})
                InvestmentDetail(vm,route.id,{stack.add(TradeDetailKey(route.account_id,it))}){stack.add(FormKey("investments",route.account_id))}
            }
            entry<TradeDetailKey> {route->
                val detail_vm:TradeDetailViewModel=viewModel(viewModelStoreOwner=owner,key="trade-detail-${route.account_id}-${route.trade_id}",factory=viewModelFactory {
                    initializer{TradeDetailViewModel(route.account_id,route.trade_id,graph.investments)}})
                val edit_vm:InvestmentsViewModel=viewModel(viewModelStoreOwner=owner,key="investment-${route.account_id}",factory=viewModelFactory {
                    initializer{InvestmentsViewModel(route.account_id,graph.investments,graph.commands,graph.clock,createSavedStateHandle())}})
                TradeDetailScreen(detail_vm,accounts.firstOrNull{it.id==route.account_id}?.name.orEmpty(),
                    {asset,trade->edit_vm.begin("EDIT_TRADE",asset,trade=trade);stack.add(FormKey("investments",route.account_id))},
                    {asset,trade->edit_vm.begin("DELETE_TRADE",asset,trade=trade);stack.add(FormKey("investments",route.account_id))})
            }
            entry<DepositDetailKey> {route->
                val detail_vm:DepositDetailViewModel=viewModel(viewModelStoreOwner=owner,key="deposit-detail-${route.account_id}-${route.deposit_id}",factory=viewModelFactory {
                    initializer{DepositDetailViewModel(route.account_id,route.deposit_id,graph.deposits,graph.clock)}})
                val edit_vm:DepositsViewModel=viewModel(viewModelStoreOwner=owner,key="deposit-${route.account_id}",factory=viewModelFactory {
                    initializer{DepositsViewModel(route.account_id,graph.deposits,graph.commands,graph.clock,createSavedStateHandle())}})
                DepositDetailScreen(detail_vm,accounts.firstOrNull{it.id==route.account_id}?.name.orEmpty(),
                    {edit_vm.begin_edit(it);stack.add(FormKey("deposits",route.account_id))},
                    {edit_vm.begin(it);stack.add(FormKey("deposits",route.account_id))})
            }
            entry<CashKey> {route->
                val vm:CashViewModel=viewModel(viewModelStoreOwner=owner,key="cash-${route.account_id}",factory=viewModelFactory {
                    initializer{CashViewModel(route.account_id,graph.cash,graph.commands,createSavedStateHandle(),graph.clock)}})
                CashDetail(vm,route.currency,{stack.add(FormKey("cash",route.account_id))},{id->
                    stack.add(CashEntryKey(route.account_id,id))
                })
            }
            entry<CashEntryKey> {route->
                val detail_vm:CashEntryViewModel=viewModel(viewModelStoreOwner=owner,key="cash-entry-${route.account_id}-${route.entry_id}",factory=viewModelFactory {
                    initializer{CashEntryViewModel(route.account_id,route.entry_id,graph.cash)}})
                val cash_vm:CashViewModel=viewModel(viewModelStoreOwner=owner,key="cash-${route.account_id}",factory=viewModelFactory {
                    initializer{CashViewModel(route.account_id,graph.cash,graph.commands,createSavedStateHandle(),graph.clock)}})
                CashEntryDetailScreen(detail_vm,accounts.firstOrNull{it.id==route.account_id}?.name.orEmpty(),{entry->
                    if(entry.editable){cash_vm.begin_entry(entry);stack.add(FormKey("cash",route.account_id))}
                    else entry.source_id?.let {
                        when(entry.source_kind) {
                            "TRADE"->stack.add(TradeEditKey(route.account_id,it))
                            "TERM_OPEN","TERM_CLOSE"->stack.add(DepositEditKey(route.account_id,it))
                        }
                    }
                })
            }
            entry<TradeEditKey> {route->
                val vm:InvestmentsViewModel=viewModel(viewModelStoreOwner=owner,key="source-trade-${route.account_id}-${route.trade_id}",factory=viewModelFactory {
                    initializer{InvestmentsViewModel(route.account_id,graph.investments,graph.commands,graph.clock,createSavedStateHandle())}})
                var ready by remember {mutableStateOf(false)}
                var attempt by remember {mutableIntStateOf(0)}
                LaunchedEffect(route.trade_id,attempt){vm.prepare_trade_source(route.trade_id);ready=true}
                if(ready)InvestmentForm(vm,accounts.firstOrNull{it.id==route.account_id}?.name.orEmpty(),back,{ready=false;attempt++})
                else Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){CircularProgressIndicator()}
            }
            entry<DepositEditKey> {route->
                val vm:DepositsViewModel=viewModel(viewModelStoreOwner=owner,key="source-deposit-${route.account_id}-${route.deposit_id}",factory=viewModelFactory {
                    initializer{DepositsViewModel(route.account_id,graph.deposits,graph.commands,graph.clock,createSavedStateHandle())}})
                var ready by remember {mutableStateOf(false)}
                var attempt by remember {mutableIntStateOf(0)}
                LaunchedEffect(route.deposit_id,attempt){vm.prepare_source(route.deposit_id);ready=true}
                if(ready)DepositForm(vm,accounts.firstOrNull{it.id==route.account_id}?.name.orEmpty(),back,{ready=false;attempt++})
                else Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.Center){CircularProgressIndicator()}
            }
            entry<FormKey> {route->
                val name=accounts.firstOrNull{it.id==route.account_id}?.name.orEmpty()
                when(route.area) {
                    "accounts"->AccountForm(accounts_vm,back)
                    "cash"->{
                        val vm:CashViewModel=viewModel(viewModelStoreOwner=owner,key="cash-${route.account_id}",factory=viewModelFactory{
                            initializer{CashViewModel(route.account_id,graph.cash,graph.commands,createSavedStateHandle(),graph.clock)}})
                        CashForm(vm,back)
                    }
                    "deposits"->{
                        val vm:DepositsViewModel=viewModel(viewModelStoreOwner=owner,key="deposit-${route.account_id}",factory=viewModelFactory{
                            initializer{DepositsViewModel(route.account_id,graph.deposits,graph.commands,graph.clock,createSavedStateHandle())}})
                        DepositForm(vm,name,back)
                    }
                    else->{
                        val vm:InvestmentsViewModel=viewModel(viewModelStoreOwner=owner,key="investment-${route.account_id}",factory=viewModelFactory{
                            initializer{InvestmentsViewModel(route.account_id,graph.investments,graph.commands,graph.clock,createSavedStateHandle())}})
                        InvestmentForm(vm,name,back)
                    }
                }
            }
        })
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PageScaffold(title:String,can_back:Boolean,on_back:()->Unit,on_edit:(()->Unit)?,content:@Composable ()->Unit) {
    val bar_height=(64f*LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    Scaffold(topBar={TopAppBar(title={Text(title,maxLines=2)},expandedHeight=bar_height,navigationIcon={
        if(can_back)TopBarAction(stringResource(R.string.back),on_back)
    },actions={if(on_edit!=null)TopBarAction(stringResource(R.string.edit),on_edit)})}) {padding->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {content()}
    }
}
