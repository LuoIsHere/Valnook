package dev.valnook.feature.deposits
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.feature.deposits.R as DepositsR
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as Decimal
import java.time.*

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

@Composable fun DepositsScreen(vm:DepositsViewModel,on_form:()->Unit,on_open:(Long)->Unit,on_archive:()->Unit={},closed:Boolean=false) {
    val state by vm.deposits.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){vm.refresh_today()}
    LaunchedEffect(Unit){while(true){delay(30000);vm.refresh_today()}}
    when (val current = state) {
        DepositsState.Loading -> CircularProgressIndicator()
        DepositsState.Failed -> Text(stringResource(DepositsR.string.deposits_load_failed))
        is DepositsState.Ready -> DepositsContent(current.rows,today,on_form,vm::load_more,on_open,on_archive,closed,current.hasMore)
    }
}
@Composable fun DepositsContent(rows:List<TermDeposit>,today:Long,on_form:()->Unit,on_more:()->Unit,
    on_open:(Long)->Unit={},on_archive:(()->Unit)?=null,closed:Boolean=false,hasMore:Boolean=rows.size>=50) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(Space.md,Space.md),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(closed)item{Text(stringResource(R.string.settled_deposits),style=MaterialTheme.typography.titleLarge)}
        else if(on_archive!=null)item{ActionButton(onClick=on_archive,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.settled_deposits))}}
        item{Text(stringResource(R.string.term_formula),style=MaterialTheme.typography.bodyMedium)}
        if(rows.isEmpty())item{EmptyState(stringResource(if(closed)R.string.empty_settled_deposits else R.string.empty_deposits))}
        items(rows,key={it.id}) { d ->
            Card(onClick={on_open(d.id)},modifier=Modifier.fillMaxWidth().testTag("deposit-record-${d.id}")) {
                Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                    AmountText(Decimal.format_display(d.principal_minor,d.currency.fraction_digits),d.currency.code)
                    Text(stringResource(R.string.deposit_detail,Decimal.format_e8(d.annual_rate_percent_e8),
                        LocalDate.ofEpochDay(d.start_epoch_day),LocalDate.ofEpochDay(d.end_epoch_day)))
                    Text(stringResource(R.string.interest_value,Decimal.format_units(d.expected_interest_minor,d.currency.fraction_digits),d.currency.code))
                    val progress=Decimal.progress(d.start_epoch_day,d.end_epoch_day,today)
                    LinearProgressIndicator(progress={progress.toFloat()},modifier=Modifier.fillMaxWidth())
                    Text(stringResource(R.string.progress_value,progress.multiply(java.math.BigDecimal("100")).toInt()))
                    Text(deposit_status(d,today))
                }
            }
        }
        if(hasMore)item{TextButton(onClick=on_more){Text(stringResource(R.string.load_more))}}
        if(!closed)item{Button(onClick=on_form,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.open_deposit))}}
    }
}
@Preview(showBackground=true,widthDp=360)
@Preview(showBackground=true,widthDp=420,fontScale=2f,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun DepositsPreview(){ValnookTheme{DepositsContent(listOf(
    TermDeposit(1,1,Currency.of("CNY"),1000000,300000000,20454,20544,7397,false,true,null),
    TermDeposit(2,1,Currency.of("USD"),500000,200000000,20454,20544,2466,true,false,true)),
    20544,{},{})}}
