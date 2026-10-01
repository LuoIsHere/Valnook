package dev.valnook.feature.deposits
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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

@Composable fun DepositsScreen(vm:DepositsViewModel,on_form:()->Unit,on_archive:()->Unit={},closed:Boolean=false) {
    val rows by (if(closed)vm.settled else vm.deposits).collectAsStateWithLifecycle();val today by vm.today.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){vm.refresh_today()}
    LaunchedEffect(Unit){while(true){delay(30000);vm.refresh_today()}}
    DepositsContent(rows,today,{vm.begin(it);on_form()},vm::load_more,{vm.begin_edit(it);on_form()},on_archive,closed)
}
@Composable fun DepositsContent(rows:List<TermDeposit>,today:Long,on_form:(TermDeposit?)->Unit,on_more:()->Unit,
    on_edit:((TermDeposit)->Unit)?=null,on_archive:(()->Unit)?=null,closed:Boolean=false) {
    LazyColumn(contentPadding=PaddingValues(Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(closed)item{Text(stringResource(R.string.settled_deposits),style=MaterialTheme.typography.titleLarge)}
        else if(on_archive!=null)item{ActionButton(onClick=on_archive,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settled_deposits))}}
        item{Text(stringResource(R.string.term_formula),style=MaterialTheme.typography.bodyMedium)}
        if(rows.isEmpty())item{EmptyState(stringResource(if(closed)R.string.empty_settled_deposits else R.string.empty_deposits))}
        items(rows,key={it.id}) { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                    AmountText(Decimal.format_display(d.principal_minor,d.currency.fraction_digits),d.currency.code)
                    Text(stringResource(R.string.deposit_detail,Decimal.format_e8(d.annual_rate_percent_e8),
                        LocalDate.ofEpochDay(d.start_epoch_day),LocalDate.ofEpochDay(d.end_epoch_day)))
                    Text(stringResource(R.string.interest_value,Decimal.format_units(d.expected_interest_minor,d.currency.fraction_digits),d.currency.code))
                    val progress=Decimal.progress(d.start_epoch_day,d.end_epoch_day,today)
                    LinearProgressIndicator(progress={progress.toFloat()},modifier=Modifier.fillMaxWidth())
                    Text(stringResource(R.string.progress_value,progress.multiply(java.math.BigDecimal("100")).toInt()))
                    Text(stringResource(when {d.closed->R.string.closed;today<d.start_epoch_day->R.string.upcoming;
                        today>=d.end_epoch_day->R.string.matured;else->R.string.active}))
                    if(!d.closed&&today>=d.end_epoch_day)
                        ActionButton(onClick={on_form(d)}){Text(stringResource(R.string.close_deposit))}
                    if(on_edit!=null)ActionButton(onClick={on_edit(d)}){Text(stringResource(R.string.edit_deposit))}
                }
            }
        }
        if(rows.size>=50)item{TextButton(onClick=on_more){Text(stringResource(R.string.load_more))}}
        if(!closed)item{Button(onClick={on_form(null)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.open_deposit))}}
    }
}
@Composable fun DepositForm(vm:DepositsViewModel,account:String,on_back:()->Unit,on_reload:(()->Unit)?=null) {
    val state by vm.draft.collectAsStateWithLifecycle(); val f=state.fields;val enabled=!state.busy&&!state.locked
    if(f.isEmpty()) {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
            ErrorMessage(state.error)
            if(on_reload!=null)Button(onClick=on_reload){Text(stringResource(R.string.retry_load))}
            TextButton(onClick=on_back){Text(stringResource(R.string.back))}
        }
        return
    }
    LaunchedEffect(state.completed){if(state.completed&&vm.consume_completion())on_back()}
    FormPanel(stringResource(if(vm.editing)R.string.edit_deposit else if(vm.closing)R.string.close_deposit else R.string.open_deposit),state,on_back,vm::submit) {
        Text(account,style=MaterialTheme.typography.titleMedium)
        if(!vm.closing) {
            CurrencyChoice(f["currency"].orEmpty(),{vm.field("currency",it)},enabled&&!vm.editing,options=Currency.supported.map{it.code to it.name})
            Field(stringResource(R.string.principal),f["principal"].orEmpty(),{vm.field("principal",it)},true,enabled)
            Field(stringResource(R.string.rate),f["rate"].orEmpty(),{vm.field("rate",it)},true,enabled)
            DateField(stringResource(R.string.start_date),f["start"].orEmpty(),{vm.field("start",it)},enabled)
            DateField(stringResource(R.string.end_date),f["end"].orEmpty(),{vm.field("end",it)},enabled)
            Text(stringResource(R.string.term_formula))
            vm.preview()?.let{Text(stringResource(R.string.interest_value,it,f["currency"].orEmpty()))}
        }
        CashLinkOption(f["linked"].toBoolean(),{vm.field("linked",it.toString())},account,f["currency"].orEmpty(),
            if(vm.closing) "+${f["return"].orEmpty()}" else "-${f["principal"].orEmpty()}",enabled,
            label=if(vm.editing)stringResource(R.string.deposit_open_link) else null)
        if(vm.editing&&vm.was_closed)CashLinkOption(f["close_linked"].toBoolean(),{vm.field("close_linked",it.toString())},
            account,f["currency"].orEmpty(),"+"+vm.return_preview().orEmpty(),enabled,stringResource(R.string.deposit_close_link))
        if(vm.editing) {
            Text(stringResource(R.string.deposit_edit_hint),style=MaterialTheme.typography.bodyMedium)
            vm.cash_change_preview()?.let{Text(stringResource(R.string.net_cash_change,it,f["currency"].orEmpty()),style=MaterialTheme.typography.titleMedium)}
        }
        Text(stringResource(R.string.form_hint),style=MaterialTheme.typography.bodySmall)
    }
}
@Preview(showBackground=true,widthDp=360)
@Preview(showBackground=true,widthDp=420,fontScale=2f,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun DepositsPreview(){ValnookTheme{DepositsContent(listOf(
    TermDeposit(1,1,Currency.of("CNY"),1000000,300000000,20454,20544,7397,false,true,null),
    TermDeposit(2,1,Currency.of("USD"),500000,200000000,20454,20544,2466,true,false,true)),
    20544,{},{})}}
