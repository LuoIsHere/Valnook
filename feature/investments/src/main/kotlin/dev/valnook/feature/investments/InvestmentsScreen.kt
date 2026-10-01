package dev.valnook.feature.investments
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as Decimal
import java.time.*

import java.time.format.DateTimeFormatter

@Composable fun InvestmentsScreen(vm:InvestmentsViewModel,on_open:(Long)->Unit,on_form:()->Unit,
    on_archive:(InvestmentSection)->Unit={},section:InvestmentSection=InvestmentSection.HOLDING) {
    val rows by vm.assets(section).collectAsStateWithLifecycle(); val types by vm.types.collectAsStateWithLifecycle()
    LazyColumn(contentPadding=PaddingValues(Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(section==InvestmentSection.HOLDING)item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Space.sm)) {
                ActionButton(onClick={on_archive(InvestmentSection.CLOSED)},modifier=Modifier.weight(1f)){Text(stringResource(R.string.closed_investments))}
                ActionButton(onClick={on_archive(InvestmentSection.PENDING)},modifier=Modifier.weight(1f)){Text(stringResource(R.string.pending_investments))}
            }
        } else item{Text(stringResource(if(section==InvestmentSection.CLOSED)R.string.closed_investments else R.string.pending_investments),style=MaterialTheme.typography.titleLarge)}
        if(rows.isEmpty())item{EmptyState(stringResource(when(section){InvestmentSection.HOLDING->R.string.empty_investments;InvestmentSection.CLOSED->R.string.empty_closed_investments;InvestmentSection.PENDING->R.string.empty_pending_investments}))}
        items(rows,key={it.id}){asset->InvestmentCard(asset,{on_open(asset.id)})}
        if(rows.size>=50)item{TextButton(onClick=vm::load_more_assets){Text(stringResource(R.string.load_more))}}
        if(section==InvestmentSection.HOLDING) {
        item{Button(onClick={vm.begin("CREATE");on_form()},enabled=types.isNotEmpty(),modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.new_investment))}}
        item{ActionButton(onClick={vm.begin("TYPE");on_form()},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.new_type))}}
        items(types,key={-it.id}){type->ActionButton(onClick={vm.begin("TYPE",type=type);on_form()}){Text(stringResource(R.string.edit_type)+" · "+type.name)}}
        }
    }
}
@Composable fun InvestmentCard(asset:Investment,on_open:(()->Unit)?=null) {
    val content:@Composable ColumnScope.()->Unit = {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
            Text(asset.name,style=MaterialTheme.typography.titleLarge)
            Text(listOf(asset.symbol,asset.type_name).filter{it.isNotBlank()}.joinToString(" · "))
            Text(stringResource(R.string.holding_value,Decimal.format_e8(asset.holding_quantity_e8)))
            val amount=runCatching{Decimal.format_display(Decimal.amount(asset.holding_quantity_e8,asset.current_price_e8,asset.currency),asset.currency.fraction_digits)}.getOrNull()
            if(amount!=null)Text(stringResource(R.string.valuation_value,amount,asset.currency.code))
            else ErrorMessage("OVERFLOW")
            Text(stringResource(R.string.manual_price_value,Decimal.format_e8(asset.current_price_e8),asset.currency.code,
                Instant.ofEpochMilli(asset.price_updated_at_ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),
                style=MaterialTheme.typography.bodyMedium)
        }
    }
    if(on_open==null)OutlinedCard(Modifier.fillMaxWidth(),content=content)
    else OutlinedCard(onClick=on_open,modifier=Modifier.fillMaxWidth(),content=content)
}
@Composable fun InvestmentDetail(vm:InvestmentsViewModel,id:Long,on_trade:(Long)->Unit,on_form:()->Unit) {
    val detail by vm.detail.collectAsStateWithLifecycle();val trades by vm.trades.collectAsStateWithLifecycle()
    val profit by vm.profit.collectAsStateWithLifecycle()
    val error by vm.history_error.collectAsStateWithLifecycle()
    LaunchedEffect(id){vm.watch_history(id)}
    val asset=detail?:return
    LazyColumn(contentPadding=PaddingValues(Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        item{InvestmentCard(asset)}
        item{ProfitSummaryCard(profit,asset.currency.code)}
        item{Column(verticalArrangement=Arrangement.spacedBy(Space.sm)) {
            listOf("PRICE" to R.string.price_update,"EDIT" to R.string.edit_investment,"BUY" to R.string.buy,"SELL" to R.string.sell).forEach{(mode,label)->
                ActionButton(onClick={vm.begin(mode,asset);on_form()},modifier=Modifier.fillMaxWidth()){Text(stringResource(label))}
            }
            if(asset.opening_quantity_e8>0)ActionButton(onClick={vm.begin("COST",asset);on_form()},modifier=Modifier.fillMaxWidth()){
                Text(stringResource(R.string.edit_opening_cost))}
        }}
        item{Text(stringResource(R.string.history),style=MaterialTheme.typography.titleLarge)}
        if(trades.isEmpty())item{EmptyState(stringResource(R.string.empty_trades))}
        itemsIndexed(trades,key={_,trade->trade.id}){index,trade->
            val date=Instant.ofEpochMilli(trade.occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()
            val previous=trades.getOrNull(index-1)?.let{Instant.ofEpochMilli(it.occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()}
            Column(verticalArrangement=Arrangement.spacedBy(Space.sm)) {
            if(date!=previous)Text(date.toString(),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
            TradeHistoryItem(trade){on_trade(trade.id)}
            }
        }
        item{ErrorMessage(error)}
        item{TextButton(onClick=vm::refresh_history){Text(stringResource(R.string.refresh))}}
        if(trades.size==50)item{TextButton(onClick=vm::next_page){Text(stringResource(R.string.load_more))}}
    }
}
@Composable fun InvestmentForm(vm:InvestmentsViewModel,account:String,on_back:()->Unit,on_reload:(()->Unit)?=null) {
    val state by vm.draft.collectAsStateWithLifecycle();val types by vm.types.collectAsStateWithLifecycle()
    val f=state.fields;val enabled=!state.busy&&!state.locked;val mode=vm.mode
    if(f.isEmpty()) {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
            ErrorMessage(state.error)
            if(on_reload!=null)Button(onClick=on_reload){Text(stringResource(R.string.retry_load))}
            TextButton(onClick=on_back){Text(stringResource(R.string.back))}
        }
        return
    }
    LaunchedEffect(state.completed){if(state.completed&&vm.consume_completion())on_back()}
    FormPanel(stringResource(when(mode){"TYPE"->R.string.asset_type;"EDIT"->R.string.edit_investment;"PRICE"->R.string.price_update;"COST"->R.string.edit_opening_cost;"BUY"->R.string.buy;"SELL"->R.string.sell;"EDIT_TRADE"->R.string.edit_trade;"DELETE_TRADE"->R.string.delete_trade;else->R.string.new_investment}),state,on_back,vm::submit,
        submit_label=if(mode=="DELETE_TRADE")stringResource(R.string.confirm_delete_trade) else null) {
        Text(account,style=MaterialTheme.typography.titleMedium)
        if(mode=="DELETE_TRADE") {
            Text(f["name"].orEmpty(),style=MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.trade_delete_hint))
            Text(f["day"].orEmpty()+" "+f["time"].orEmpty()+" · "+
                stringResource(if(f["direction"]=="BUY")R.string.buy else R.string.sell)+" · "+f["quantity"].orEmpty()+" × "+f["price"].orEmpty())
        }
        if(mode=="EDIT_TRADE")ChoiceField(stringResource(R.string.trade_direction),f["direction"].orEmpty(),
            listOf("BUY" to stringResource(R.string.buy),"SELL" to stringResource(R.string.sell)),{vm.field("direction",it)},enabled)
        if(mode in listOf("TYPE","EDIT","CREATE"))Field(stringResource(R.string.name),f["name"].orEmpty(),{vm.field("name",it)},enabled=enabled)
        if(mode in listOf("EDIT","CREATE")) {
            Field(stringResource(R.string.symbol),f["symbol"].orEmpty(),{vm.field("symbol",it)},enabled=enabled)
            ChoiceField(stringResource(R.string.asset_type),f["type"].orEmpty(),types.map{it.id.toString() to it.name},
                {vm.field("type",it)},enabled)
        }
        if(mode=="CREATE") {
            CurrencyChoice(f["currency"].orEmpty(),{vm.field("currency",it)},enabled,options=Currency.supported.map{it.code to it.name})
            Text(stringResource(R.string.opening_hint))
        }
        if(mode in listOf("CREATE","BUY","SELL","EDIT_TRADE"))Field(stringResource(if(mode=="CREATE")R.string.opening_quantity else R.string.quantity),f["quantity"].orEmpty(),{vm.field("quantity",it)},true,enabled)
        if(mode=="COST" || (mode=="CREATE" && runCatching{Decimal.parse_e8(f["quantity"].orEmpty())>0}.getOrDefault(false))) {
            Field(stringResource(R.string.opening_cost)+" · "+f["currency"].orEmpty(),f["opening_cost"].orEmpty(),{vm.field("opening_cost",it)},true,enabled)
            Text(stringResource(R.string.opening_cost_required),style=MaterialTheme.typography.bodySmall)
        }
        if(mode in listOf("CREATE","PRICE","BUY","SELL","EDIT_TRADE"))
            Field(stringResource(if(mode in listOf("BUY","SELL","EDIT_TRADE"))R.string.execution_price else R.string.current_price)+" · "+f["currency"].orEmpty(),
                f["price"].orEmpty(),{vm.field("price",it)},true,enabled)
        if(mode in listOf("BUY","SELL","EDIT_TRADE")) {
            DateField(stringResource(R.string.trade_date),f["day"].orEmpty(),{vm.field("day",it)},enabled)
            TimeField(stringResource(R.string.record_time),f["time"].orEmpty(),{vm.field("time",it)},enabled)
            val amount=vm.amount_preview().orEmpty()
            Text(stringResource(R.string.amount_preview,amount,f["currency"].orEmpty()))
            CashLinkOption(f["linked"].toBoolean(),{vm.field("linked",it.toString())},account,f["currency"].orEmpty(),
                (if(mode=="BUY"||f["direction"]=="BUY")"-" else "+")+amount,enabled)
        }
        if(mode in listOf("EDIT_TRADE","DELETE_TRADE"))vm.cash_change_preview()?.let{
            Text(stringResource(R.string.net_cash_change,it,f["currency"].orEmpty()),style=MaterialTheme.typography.titleMedium)
        }
    }
}
@Preview(showBackground=true,widthDp=360,fontScale=2f)
@Preview(showBackground=true,widthDp=420,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun InvestmentPreview(){ValnookTheme{InvestmentCard(Investment(1,1,1,"自定义基金","名称很长的合成资产 / 多币种价格","",
    Currency.of("USD"),0,0,12000000000,0),{})}}
