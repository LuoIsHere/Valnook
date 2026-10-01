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
import java.time.*

@Composable fun CashScreen(vm:CashViewModel,on_open:(String)->Unit) {
    val state by vm.balances.collectAsStateWithLifecycle()
    when (val current = state) {
        CashBalancesState.Loading -> CircularProgressIndicator()
        CashBalancesState.Failed -> Text("余额读取失败，请返回后重试")
        is CashBalancesState.Ready -> CashContent(current.rows) { on_open(it.currency.code) }
    }
}
@Composable fun CashContent(rows:List<CashBalance>,on_open:(CashBalance)->Unit) {
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        if(rows.isEmpty())item{EmptyState(stringResource(R.string.empty_cash))}
        items(rows,key={it.currency.code}) {cash->
            val history_label=stringResource(R.string.cash_changes)+" · "+cash.currency.code
            OutlinedCard(onClick={on_open(cash)},modifier=Modifier.fillMaxWidth()
                .semantics{contentDescription=history_label}) {
                CashBalanceSummary(cash.currency,cash.balance_minor,null)
            }
        }
    }
}
@Composable fun CashDetail(vm:CashViewModel,code:String,on_form:()->Unit,on_entry:(Long)->Unit) {
    val balanceState by vm.balances.collectAsStateWithLifecycle()
    val ledgerState by vm.entries.collectAsStateWithLifecycle()
    LaunchedEffect(code){vm.watch_currency(code)}
    if (balanceState == CashBalancesState.Failed || ledgerState == CashLedgerState.Failed) {
        Text("现金记录读取失败，请返回后重试")
        return
    }
    val balances = (balanceState as? CashBalancesState.Ready)?.rows
    val ledger = ledgerState as? CashLedgerState.Ready
    if (balances == null || ledger == null) {
        CircularProgressIndicator()
        return
    }
    val entries = ledger.rows
    val currency=Currency.of(code)
    val balance=balances.firstOrNull{it.currency.code==code}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(Space.md)) {
        item{
            OutlinedCard(Modifier.fillMaxWidth()){
                if (balance == null) Text("该币种现金账户尚未建立", Modifier.padding(Space.md))
                else CashBalanceSummary(currency,balance.balance_minor,on_edit=on_form)
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
@Composable fun CashPreview(){ValnookTheme{CashContent(listOf(CashBalance(1,Currency.of("CNY"),9223372036854775807,1),
    CashBalance(1,Currency.of("USD"),1234567,1),CashBalance(1,Currency.of("JPY"),0,1)),{})}}
