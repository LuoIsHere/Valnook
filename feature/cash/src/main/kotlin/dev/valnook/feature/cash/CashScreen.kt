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
import dev.valnook.feature.cash.R as CashR
import dev.valnook.domain.model.*
import java.time.*

@Composable fun CashScreen(vm:CashViewModel,on_open:(Long)->Unit) {
    val state by vm.balances.collectAsStateWithLifecycle()
    when (val current = state) {
        CashBalancesState.Loading -> CircularProgressIndicator()
        CashBalancesState.Failed -> Text(stringResource(CashR.string.cash_balance_load_failed))
        is CashBalancesState.Ready -> CashContent(current.rows) { on_open(it.id) }
    }
}
@Composable fun CashContent(rows:List<CashAccount>,on_open:(CashAccount)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(rows.isEmpty())item{EmptyState(stringResource(R.string.empty_cash))}
        items(rows,key={it.id}) {cash->
            val history_label=stringResource(R.string.cash_changes)+" · "+cash.name
            OutlinedCard(onClick={on_open(cash)},modifier=Modifier.fillMaxWidth()
                .semantics{contentDescription=history_label}) {
                CashBalanceSummary(cash,null)
            }
        }
    }
}
@Composable fun CashDetail(vm:CashViewModel,cashAccountId:Long,on_form:()->Unit,on_entry:(Long)->Unit) {
    val ledgerState by vm.entries.collectAsStateWithLifecycle()
    LaunchedEffect(cashAccountId){vm.watchCashAccount(cashAccountId)}
    if (ledgerState == CashLedgerState.Failed) {
        Text(stringResource(CashR.string.cash_history_load_failed))
        return
    }
    val ledger = ledgerState as? CashLedgerState.Ready
    if (ledger == null) {
        CircularProgressIndicator()
        return
    }
    val entries = ledger.rows
    val balance=ledger.account
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        item{
            OutlinedCard(Modifier.fillMaxWidth()){
                CashBalanceSummary(balance,on_edit=on_form)
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
                CashEntryItem(entry){on_entry(entry.id)}
            }
        }
        if(ledger.hasMore)item{TextButton(onClick=vm::load_more_entries){Text(stringResource(R.string.load_more))}}
    }
}
@Preview(showBackground=true,widthDp=360,fontScale=2f)
@Preview(showBackground=true,widthDp=420,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun CashPreview(){ValnookTheme{CashContent(listOf(CashAccount(1,Currency.of("CNY"),9223372036854775807,1,1,"日常现金"),
    CashAccount(1,Currency.of("USD"),1234567,1,2,"美元交易资金"),CashAccount(1,Currency.of("JPY"),0,1,3,"日元备用")),{})}}
