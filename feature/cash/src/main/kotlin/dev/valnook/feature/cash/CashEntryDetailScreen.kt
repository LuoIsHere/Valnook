package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.*
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import java.time.Instant
import java.time.ZoneId
import dev.valnook.feature.cash.R as CashR

@Composable fun CashEntryDetailScreen(vm:CashEntryViewModel,account_name:String,on_deleted:()->Unit={},on_edit:(CashEntry)->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val deletion by vm.deletion.collectAsStateWithLifecycle()
    LaunchedEffect(deletion.deleted) { if(deletion.deleted) on_deleted() }
    when(val current=state) {
        CashEntryState.Loading->PageLoading()
        // Gone because it was just deleted here: the page is on its way out, so nothing to explain.
        CashEntryState.Missing->if(!deletion.deleting&&!deletion.deleted)
            Box(Modifier.padding(Space.lg)){EmptyState(stringResource(R.string.cash_entry_unavailable))}
        CashEntryState.Failed->Column(Modifier.padding(Space.lg),verticalArrangement=Arrangement.spacedBy(Space.md)) {
            Text(stringResource(R.string.cash_entry_load_error))
            SecondaryButton(onClick=vm::retry){Text(stringResource(R.string.retry_load))}
        }
        is CashEntryState.Ready->CashEntryDetailContent(current.entry,account_name,on_delete=vm::delete,
            deleting=deletion.deleting||deletion.deleted,error=deletion.error?.name,on_edit=on_edit)
    }
}

/** [on_delete], where given, offers to delete a hand-made balance change; other entries go with their own record. */
@Composable fun CashEntryDetailContent(entry:CashEntry,account_name:String,on_delete:((CashEntry)->Unit)?=null,
    deleting:Boolean=false,error:String?=null,on_edit:(CashEntry)->Unit) {
    var confirming by remember { mutableStateOf(false) }
    val time=Instant.ofEpochMilli(entry.occurred_at_ms).atZone(ZoneId.systemDefault())
    val rows=listOf(
        stringResource(R.string.change_amount) to cash_change_text(entry),
        stringResource(R.string.entry_source) to cash_source_label(entry),
        stringResource(R.string.entry_account) to account_name,
        stringResource(R.string.currency) to entry.currency.code,
        stringResource(R.string.record_date) to time.toLocalDate().toString(),
        stringResource(R.string.record_time) to time.format(rememberClockFormatter()),
        stringResource(R.string.note) to entry.note.ifBlank{stringResource(R.string.no_note)})
    // The top bar names the page; it is not repeated here.
    RecordDetailLayout(null,rows) {
        // A deleted instrument's trade, or a bill deleted with its category, has nothing left to open.
        if(entry.source!in REMOVED_SOURCES) SecondaryButton(onClick={on_edit(entry)},modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(when(entry.source) {
                CashSource.TRADE->R.string.view_source_trade
                CashSource.DIVIDEND->R.string.view_source_dividend
                CashSource.TERM_OPEN,CashSource.TERM_CLOSE->R.string.view_source_deposit
                CashSource.BILL,CashSource.BILL_TRANSFER_IN->R.string.view_source_bill
                CashSource.CASH_SET,CashSource.TRADE_REMOVED,CashSource.DIVIDEND_REMOVED,CashSource.BILL_REMOVED->R.string.edit_cash_change
            }))
        }
        if(on_delete!=null&&entry.editable) {
            ErrorMessage(error)
            DangerButton(stringResource(CashR.string.cash_entry_delete),{confirming=true},
                Modifier.fillMaxWidth().testTag("cash-entry-delete"),enabled=!deleting)
        }
    }
    if(confirming&&on_delete!=null) ValnookDialog({confirming=false},
        confirmButton={TextButton({confirming=false;on_delete(entry)},Modifier.testTag("cash-entry-delete-confirm")) {
            Text(stringResource(CashR.string.cash_entry_delete)) } },
        dismissButton={TextButton({confirming=false}){Text(stringResource(R.string.cancel))}},
        title={Text(stringResource(CashR.string.cash_entry_delete_title))},
        // Deleting takes the change back, so the balance moves the other way by the same amount.
        text={Text(if(entry.delta_minor==0L) stringResource(CashR.string.cash_entry_delete_unchanged)
            else stringResource(CashR.string.cash_entry_delete_message,cash_change_text(entry.copy(delta_minor=-entry.delta_minor))))})
}

private val REMOVED_SOURCES = setOf(CashSource.TRADE_REMOVED, CashSource.DIVIDEND_REMOVED, CashSource.BILL_REMOVED)
