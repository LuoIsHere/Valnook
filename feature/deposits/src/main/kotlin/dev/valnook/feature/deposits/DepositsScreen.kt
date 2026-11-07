package dev.valnook.feature.deposits
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
        is DepositsState.Ready -> DepositsContent(current.rows,today,on_form,vm::load_more,on_open,on_archive,closed,current.hasMore,current.month,vm::selectMonth)
    }
}

/**
 * The deposits of one account as one card of rows, like the account's cash: open ones with a last row to open
 * another, or with [closed] the settled ones a month at a time.
 */
@Composable fun DepositsContent(rows:List<TermDeposit>,today:Long,on_form:()->Unit,on_more:()->Unit,
    on_open:(Long)->Unit={},on_archive:(()->Unit)?=null,closed:Boolean=false,hasMore:Boolean=rows.size>=50,month:YearMonth?=null,onMonth:(YearMonth)->Unit={}) {
    val listState = rememberLazyListState()
    var displayedMonth by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(month?.toString()) }
    LaunchedEffect(month) {
        if (displayedMonth != month?.toString()) { listState.scrollToItem(0); displayedMonth = month?.toString() }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(month!=null)item(key="month"){MonthPager(month,onMonth)}
        if(!closed)item(key="header") {
            Row(Modifier.fillMaxWidth().testTag("deposit-header"),verticalAlignment=Alignment.CenterVertically) {
                Text(stringResource(DepositsR.string.deposit_section_current),Modifier.weight(1f).padding(start=4.dp).semantics{heading()},
                    style=MaterialTheme.typography.bodySmall,color=muted)
                if(on_archive!=null)TextButton(on_archive,Modifier.testTag("deposit-settled-open")) {
                    Text(stringResource(R.string.settled_deposits),style=MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(4.dp));RowChevron()
                }
            }
        }
        if(closed&&rows.isEmpty())item(key="empty"){EmptyState(stringResource(R.string.empty_settled_deposits))}
        if(!closed||rows.isNotEmpty())item(key="card") {
            GlassCard(Modifier.testTag("deposit-card")) {
                rows.forEachIndexed { index,deposit ->
                    if(index>0)DepositDivider()
                    DepositRow(deposit,today){on_open(deposit.id)}
                }
                if(!closed) {
                    if(rows.isEmpty())Text(stringResource(R.string.empty_deposits),Modifier.fillMaxWidth().padding(12.dp),
                        style=MaterialTheme.typography.bodyMedium,color=muted)
                    DepositDivider()
                    Row(Modifier.fillMaxWidth().heightIn(min=52.dp).testTag("deposit-open").clickable(role=Role.Button,onClick=on_form)
                        .padding(horizontal=12.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(R.string.open_deposit),style=MaterialTheme.typography.bodyLarge,color=muted)
                    }
                }
            }
        }
        item(key="formula"){Text(stringResource(R.string.term_formula),Modifier.padding(start=4.dp,top=2.dp),
            style=MaterialTheme.typography.bodySmall,color=muted)}
        if(hasMore)item(key="more"){
            Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                if(month!=null){LaunchedEffect(rows.size,month){on_more()};CircularProgressIndicator()}
                else TextButton(onClick=on_more){Text(stringResource(R.string.load_more))}
            }
        }
    }
}

@Composable private fun DepositDivider() { HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer) }

/** Its term and state, the principal with the interest it earns, then how far along the term is. */
@Composable private fun DepositRow(deposit:TermDeposit,today:Long,on_open:()->Unit) {
    Column(Modifier.testTag("deposit-record-${deposit.id}").fillMaxWidth().clickable(onClick=on_open).padding(12.dp),
        verticalArrangement=Arrangement.spacedBy(2.dp)) {
        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),
            verticalArrangement=Arrangement.spacedBy(2.dp),itemVerticalAlignment=Alignment.CenterVertically) {
            Text(termTitle(deposit),style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            DepositStatusTag(deposit,today)
            Spacer(Modifier.weight(1f))
            Text(stringResource(DepositsR.string.deposit_rate_value,Decimal.format_e8(deposit.annual_rate_percent_e8)),
                style=MaterialTheme.typography.bodySmall.copy(fontFeatureSettings="tnum"),color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            DepositAmount(deposit.principal_minor,deposit.currency,Modifier.weight(1f))
            InterestText(deposit.expected_interest_minor,deposit.currency)
        }
        TermProgress(deposit,today,Modifier.padding(top=6.dp))
    }
}

@Preview(showBackground=true,widthDp=360)
@Preview(showBackground=true,widthDp=420,fontScale=2f,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun DepositsPreview(){ValnookTheme{DepositsContent(listOf(
    TermDeposit(1,1,Currency.of("CNY"),1000000,300000000,20454,20544,7397,false,true,null),
    TermDeposit(2,1,Currency.of("USD"),500000,200000000,20454,20544,2466,true,false,true)),
    20544,{},{},on_archive={})}}
