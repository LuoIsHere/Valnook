package dev.valnook.feature.deposits

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.core.designsystem.R
import dev.valnook.feature.deposits.R as DepositsR
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.*
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.money.DecimalRules as Decimal
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable internal fun deposit_status(deposit:TermDeposit,today:Long)=stringResource(when {
    deposit.closed->R.string.closed
    today<deposit.start_epoch_day->R.string.upcoming
    today>=deposit.end_epoch_day->R.string.matured
    else->R.string.active
})

@Composable fun DepositDetailScreen(vm:DepositDetailViewModel,account_name:String,on_edit:(TermDeposit)->Unit,on_close:(TermDeposit)->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME){vm.refresh_today()}
    LaunchedEffect(Unit){while(true){delay(30000);vm.refresh_today()}}
    when(val current=state) {
        DepositDetailState.Loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        DepositDetailState.Missing->Box(Modifier.padding(Space.lg)){EmptyState(stringResource(R.string.deposit_unavailable))}
        DepositDetailState.Failed->Column(Modifier.padding(Space.lg),verticalArrangement=Arrangement.spacedBy(Space.md)) {
            Text(stringResource(R.string.record_load_error))
            ActionButton(onClick=vm::retry){Text(stringResource(R.string.retry_load))}
        }
        is DepositDetailState.Ready->DepositDetailContent(current.deposit,today,account_name,
            {on_edit(current.deposit)},{on_close(current.deposit)})
    }
}

/**
 * One deposit: its term, principal and interest over how far along it is, then its details, then what can be done
 * with it. Settling is offered once it has matured.
 */
@Composable fun DepositDetailContent(deposit:TermDeposit,today:Long,account_name:String,on_edit:()->Unit,on_close:()->Unit) {
    val currency=deposit.currency
    val start=LocalDate.ofEpochDay(deposit.start_epoch_day)
    val end=LocalDate.ofEpochDay(deposit.end_epoch_day)
    val days=java.time.temporal.ChronoUnit.DAYS.between(start,end).toInt()
    val total=runCatching{Decimal.add(deposit.principal_minor,deposit.expected_interest_minor)}.getOrNull()
    val rows=listOf(
        stringResource(R.string.entry_account) to account_name,
        stringResource(DepositsR.string.deposit_rate_label) to (Decimal.format_e8(deposit.annual_rate_percent_e8)+"%"),
        stringResource(R.string.start_date) to start.toString(),
        stringResource(R.string.end_date) to end.toString(),
        stringResource(DepositsR.string.deposit_term_section) to
            androidx.compose.ui.res.pluralStringResource(DepositsR.plurals.deposit_term_days,days,days),
        stringResource(DepositsR.string.deposit_summary_total) to (total?.let{minorText(it,currency)+" "+currency.code}?:"—"),
        stringResource(R.string.deposit_open_link) to stringResource(if(deposit.open_cash_linked)R.string.linked else R.string.unlinked),
        stringResource(R.string.deposit_close_link) to stringResource(when(deposit.close_cash_linked) {
            true->R.string.linked;false->R.string.unlinked;null->R.string.deposit_not_settled
        }))
    val title=stringResource(R.string.deposit_detail_title)
    val titleInBar=title==LocalPageTitle.current
    LazyColumn(Modifier.fillMaxSize().testTag("deposit-detail-list"),contentPadding=pageContentPadding(),
        verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(!titleInBar)item(key="title"){Text(title,style=MaterialTheme.typography.titleLarge)}
        item(key="summary") {
            GlassCard(Modifier.testTag("deposit-detail-summary")) {
                Column(Modifier.fillMaxWidth().padding(Space.cardInset),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(2.dp),
                        itemVerticalAlignment=Alignment.CenterVertically) {
                        Text(termTitle(deposit),style=MaterialTheme.typography.bodyMedium)
                        DepositStatusTag(deposit,today)
                    }
                    DepositAmount(deposit.principal_minor,currency,Modifier.testTag("deposit-detail-principal"),
                        MaterialTheme.typography.headlineMedium)
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.interest),style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        InterestText(deposit.expected_interest_minor,currency,withCode=true)
                    }
                    TermProgress(deposit,today,Modifier.padding(top=8.dp))
                }
            }
        }
        item(key="info") {
            FormSection(stringResource(DepositsR.string.deposit_info_section)) {
                Column(Modifier.fillMaxWidth().padding(horizontal=Space.cardInset,vertical=Space.xs)) {
                    rows.forEachIndexed { index,(label,value)->
                        DetailRow(label,value)
                        if(index<rows.lastIndex)HorizontalDivider(color=MaterialTheme.colorScheme.surfaceContainer)
                    }
                }
            }
        }
        item(key="actions") {
            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                if(!deposit.closed&&today>=deposit.end_epoch_day)
                    Button(onClick=on_close,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(stringResource(R.string.close_deposit))}
                ActionButton(onClick=on_edit,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)){Text(stringResource(R.string.edit_deposit))}
            }
        }
    }
}
