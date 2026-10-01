package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as Decimal
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun CashScreen(vm:CashViewModel,on_open:(String)->Unit={},on_form:()->Unit) {
    val rows by vm.balances.collectAsStateWithLifecycle()
    CashContent(rows,{vm.begin(it);on_form()},{on_open(it.currency.code)})
}
@Composable fun CashContent(rows:List<CashBalance>,on_edit:(CashBalance?)->Unit,
    on_open:(CashBalance)->Unit={on_edit(it)}) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(rows.isEmpty())item{EmptyState(stringResource(R.string.empty_cash))}
        items(rows,key={it.currency.code}) {cash->
            val history_label=stringResource(R.string.cash_changes)+" · "+cash.currency.code
            OutlinedCard(onClick={on_open(cash)},modifier=Modifier.fillMaxWidth()
                .semantics{contentDescription=history_label}) {
                CashBalanceSummary(cash.currency,cash.balance_minor){on_edit(cash)}
            }
        }
        if(rows.size<Currency.supported.size)item{
            Button(onClick={on_edit(null)},modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.add_currency_account))}
        }
    }
}
@Composable fun CashDetail(vm:CashViewModel,code:String,on_form:()->Unit,on_source:(CashEntry)->Unit) {
    val balances by vm.balances.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    LaunchedEffect(code){vm.watch_currency(code)}
    val currency=Currency.of(code)
    val balance=balances.firstOrNull{it.currency.code==code}
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        item{
            OutlinedCard(Modifier.fillMaxWidth()){
                CashBalanceSummary(currency,balance?.balance_minor ?: 0,
                    on_edit=if(balance==null)null else {{vm.begin(balance);on_form()}})
            }
        }
        item{Text(stringResource(R.string.cash_changes),style=MaterialTheme.typography.titleLarge)}
        if(entries.isEmpty())item{EmptyState(stringResource(R.string.empty_cash_changes))}
        itemsIndexed(entries,key={_,entry->entry.id}){index,entry->
            val time=Instant.ofEpochMilli(entry.occurred_at_ms).atZone(ZoneId.systemDefault())
            val previous_date=entries.getOrNull(index-1)?.let{Instant.ofEpochMilli(it.occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()}
            Column(verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                if(previous_date!=time.toLocalDate())Text(time.toLocalDate().toString(),
                    style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                        Text((if(entry.delta_minor>0)"+" else "")+Decimal.format_display(entry.delta_minor,currency.fraction_digits)+" "+currency.code,
                            style=MaterialTheme.typography.titleLarge.copy(fontFeatureSettings="tnum"))
                        Text(stringResource(when(entry.source_kind){"TRADE"->R.string.source_trade;"TERM_OPEN"->R.string.source_deposit_open;
                            "TERM_CLOSE"->R.string.source_deposit_close;else->R.string.manual_balance_change})+
                            " · "+time.format(DateTimeFormatter.ofPattern("HH:mm")),style=MaterialTheme.typography.bodyMedium)
                        if(entry.note.isNotBlank())Text(entry.note,style=MaterialTheme.typography.bodyMedium)
                        if(entry.editable)ActionButton(onClick={vm.begin_entry(entry);on_form()}){Text(stringResource(R.string.edit_cash_change))}
                        else if(entry.source_kind=="TRADE")ActionButton(onClick={on_source(entry)}){Text(stringResource(R.string.view_source_trade))}
                        else if(entry.source_kind in listOf("TERM_OPEN","TERM_CLOSE"))ActionButton(onClick={on_source(entry)}){Text(stringResource(R.string.view_source_deposit))}
                        else Text(stringResource(R.string.source_deposit_hint),style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if(entries.size>=50)item{TextButton(onClick=vm::load_more_entries){Text(stringResource(R.string.load_more))}}
    }
}
@Composable fun CashForm(vm:CashViewModel,on_back:()->Unit) {
    val state by vm.draft.collectAsStateWithLifecycle()
    val balances by vm.balances.collectAsStateWithLifecycle()
    val enabled=!state.busy&&!state.locked
    val f=state.fields
    LaunchedEffect(state.completed){if(state.completed&&vm.consume_completion())on_back()}
    FormPanel(stringResource(if(vm.editing_entry)R.string.edit_cash_change else if(vm.existing)R.string.cash_set else R.string.add_currency_account),
        state,on_back,vm::submit) {
        CurrencyChoice(f["currency"].orEmpty(),{vm.field("currency",it)},enabled&&!vm.existing,
            options=Currency.supported.map{it.code to it.name},excluded=if(vm.existing)emptySet() else balances.map{it.currency.code}.toSet())
        if(vm.editing_entry)ChoiceField(stringResource(R.string.change_direction),f["direction"].orEmpty(),
            listOf("INCREASE" to stringResource(R.string.increase),"DECREASE" to stringResource(R.string.decrease)),
            {vm.field("direction",it)},enabled)
        Field(stringResource(if(vm.editing_entry)R.string.change_amount else R.string.balance),
            f["amount"].orEmpty(),{vm.field("amount",it)},true,enabled)
        if(vm.editing_entry) {
            DateField(stringResource(R.string.record_date),f["day"].orEmpty(),{vm.field("day",it)},enabled)
            TimeField(stringResource(R.string.record_time),f["time"].orEmpty(),{vm.field("time",it)},enabled)
            Field(stringResource(R.string.note),f["note"].orEmpty(),{vm.field("note",it)},enabled=enabled)
            Text(stringResource(R.string.cash_edit_hint),style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        vm.change_preview()?.let{Text(stringResource(R.string.net_cash_change,it,f["currency"].orEmpty()),
            style=MaterialTheme.typography.titleMedium)}
    }
}
@Preview(showBackground=true,widthDp=360,fontScale=2f)
@Preview(showBackground=true,widthDp=420,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun CashPreview(){ValnookTheme{CashContent(listOf(CashBalance(1,Currency.of("CNY"),9223372036854775807,1),
    CashBalance(1,Currency.of("USD"),1234567,1),CashBalance(1,Currency.of("JPY"),0,1)),{})}}
