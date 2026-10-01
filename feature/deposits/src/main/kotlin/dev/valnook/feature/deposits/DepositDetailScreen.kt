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

@Composable fun DepositDetailContent(deposit:TermDeposit,today:Long,account_name:String,on_edit:()->Unit,on_close:()->Unit) {
    val currency=deposit.currency
    val progress=Decimal.progress(deposit.start_epoch_day,deposit.end_epoch_day,today)
    val rows=listOf(
        stringResource(R.string.entry_account) to account_name,
        stringResource(R.string.principal) to (Decimal.format_display(deposit.principal_minor,currency.fraction_digits)+" "+currency.code),
        stringResource(R.string.currency) to (currency.code+" · "+currency.name),
        stringResource(R.string.rate) to Decimal.format_e8(deposit.annual_rate_percent_e8),
        stringResource(R.string.start_date) to LocalDate.ofEpochDay(deposit.start_epoch_day).toString(),
        stringResource(R.string.end_date) to LocalDate.ofEpochDay(deposit.end_epoch_day).toString(),
        stringResource(R.string.interest) to (Decimal.format_display(deposit.expected_interest_minor,currency.fraction_digits)+" "+currency.code),
        stringResource(R.string.record_status) to deposit_status(deposit,today),
        stringResource(R.string.record_progress) to (progress.multiply(java.math.BigDecimal("100")).toInt().toString()+"%"),
        stringResource(R.string.deposit_open_link) to stringResource(if(deposit.open_cash_linked)R.string.linked else R.string.unlinked),
        stringResource(R.string.deposit_close_link) to stringResource(when(deposit.close_cash_linked) {
            true->R.string.linked;false->R.string.unlinked;null->R.string.deposit_not_settled
        }))
    RecordDetailLayout(stringResource(R.string.deposit_detail_title),rows) {
        ActionButton(onClick=on_edit,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.edit_deposit))}
        if(!deposit.closed&&today>=deposit.end_epoch_day) {
            ActionButton(onClick=on_close,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.close_deposit))}
        }
    }
}
