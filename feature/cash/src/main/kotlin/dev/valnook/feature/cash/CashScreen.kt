package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.feature.cash.R as CashR
import dev.valnook.domain.model.*
import java.time.*

@Composable fun CashScreen(vm:CashViewModel,creditExpansion:()->Float={1f},accountNote:String="",on_open:(Long)->Unit) {
    val state by vm.balances.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()
    when (val current = state) {
        CashBalancesState.Loading -> CircularProgressIndicator()
        CashBalancesState.Failed -> Text(stringResource(CashR.string.cash_balance_load_failed))
        is CashBalancesState.Ready -> CashContent(current.rows, current.allAccounts,
            current.creditSourceLabels, trends, creditExpansion, accountNote) { on_open(it.id) }
    }
}
@Composable fun CashContent(rows:List<CashAccount>,allAccounts:List<CashAccount> = rows,
    creditSourceLabels:Map<Long,String> = emptyMap(),
    trends:Map<Long,List<CashTrendPoint>> = emptyMap(),
    creditExpansion:()->Float={1f},
    accountNote:String="",
    on_open:(CashAccount)->Unit) {
    val (credit,savings)=rows.partition { it.type==BalanceAccountType.CREDIT }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        if(rows.isEmpty())item{EmptyState(stringResource(R.string.empty_cash))}
        if(credit.isNotEmpty())item(key="credit") {
            CashSection(stringResource(CashR.string.cash_section_credit),Modifier.testTag("cash-section-credit")) {
                credit.forEachIndexed { index,cash ->
                    if(index>0)CashRowDivider()
                    CreditAccountRow(cash,allAccounts,creditSourceLabels,trends[cash.id].orEmpty(),creditExpansion){on_open(cash)}
                }
            }
        }
        if(savings.isNotEmpty())item(key="savings") {
            CashSection(stringResource(CashR.string.cash_section_savings),Modifier.testTag("cash-section-savings")) {
                savings.forEachIndexed { index,cash ->
                    if(index>0)CashRowDivider()
                    SavingsAccountRow(cash,trends[cash.id].orEmpty()){on_open(cash)}
                }
            }
        }
        if(accountNote.isNotBlank())item(key="info") {
            CashSection(stringResource(CashR.string.cash_section_info),Modifier.testTag("cash-section-info")) {
                Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(CashR.string.cash_note_label),style=MaterialTheme.typography.bodySmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(accountNote,Modifier.weight(1f),style=MaterialTheme.typography.bodySmall,
                        textAlign=androidx.compose.ui.text.style.TextAlign.End)
                }
            }
        }
    }
}
@Composable fun CashDetail(vm:CashViewModel,cashAccountId:Long,on_form:()->Unit,on_entry:(Long)->Unit) {
    val ledgerState by vm.entries.collectAsStateWithLifecycle()
    val trends by vm.trends.collectAsStateWithLifecycle()
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
    val listState = rememberLazyListState()
    var displayedMonth by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(ledger.month?.toString()) }
    LaunchedEffect(ledger.month) {
        if (displayedMonth != ledger.month?.toString()) { listState.scrollToItem(0); displayedMonth = ledger.month?.toString() }
    }
    LazyColumn(Modifier.fillMaxSize(),state=listState,contentPadding=pageContentPadding(),verticalArrangement=Arrangement.spacedBy(0.dp)) {
        item{
            GlassCard(Modifier.padding(bottom=Space.md), prominent=true) {
            CashBalanceSummary(balance,on_edit=on_form,allAccounts=ledger.allAccounts,
                showBalance=false,
                creditSourceLabels=ledger.creditSourceLabels,
                modifier=Modifier.fillMaxWidth().padding(16.dp))
            }
        }
        item { CashTrendChart(trends[cashAccountId].orEmpty(),balance,Modifier.fillMaxWidth().padding(bottom=Space.md)) }
        item{Text(stringResource(R.string.cash_changes),Modifier.padding(top=Space.md,bottom=Space.sm),style=MaterialTheme.typography.titleLarge)}
        item { MonthPager(ledger.month, vm::selectMonth) }
        if(entries.isEmpty())item{EmptyState(stringResource(R.string.empty_cash_changes))}
        itemsIndexed(entries,key={_,entry->entry.id}){index,entry->
            val time=Instant.ofEpochMilli(entry.occurred_at_ms).atZone(ZoneId.systemDefault())
            val previous_date=entries.getOrNull(index-1)?.let{Instant.ofEpochMilli(it.occurred_at_ms).atZone(ZoneId.systemDefault()).toLocalDate()}
            Column {
                if(previous_date!=time.toLocalDate())Text(time.toLocalDate().toString(),
                    modifier=Modifier.padding(top=Space.sm,bottom=4.dp),style=MaterialTheme.typography.labelLarge,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                CashEntryItem(entry,index<entries.lastIndex||ledger.hasMore){on_entry(entry.id)}
            }
        }
        if(ledger.hasMore)item{LaunchedEffect(entries.size,ledger.month){vm.load_more_entries()}; CircularProgressIndicator()}
    }
}
@Preview(showBackground=true,widthDp=360,fontScale=2f)
@Preview(showBackground=true,widthDp=420,uiMode=android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable fun CashPreview(){ValnookTheme{CashContent(listOf(CashAccount(1,Currency.of("CNY"),9223372036854775807,1,1,"日常现金"),
    CashAccount(1,Currency.of("USD"),1234567,1,2,"美元交易资金"),CashAccount(1,Currency.of("JPY"),0,1,3,"日元备用")),on_open={})}}
