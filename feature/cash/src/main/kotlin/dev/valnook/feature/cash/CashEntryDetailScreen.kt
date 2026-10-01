package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.*
import dev.valnook.domain.model.CashEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun CashEntryDetailScreen(vm:CashEntryViewModel,account_name:String,on_edit:(CashEntry)->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when(val current=state) {
        CashEntryState.Loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        CashEntryState.Missing->Box(Modifier.padding(Space.lg)){EmptyState(stringResource(R.string.cash_entry_unavailable))}
        CashEntryState.Failed->Column(Modifier.padding(Space.lg),verticalArrangement=Arrangement.spacedBy(Space.md)) {
            Text(stringResource(R.string.cash_entry_load_error))
            ActionButton(onClick=vm::retry){Text(stringResource(R.string.retry_load))}
        }
        is CashEntryState.Ready->CashEntryDetailContent(current.entry,account_name,on_edit)
    }
}

@Composable fun CashEntryDetailContent(entry:CashEntry,account_name:String,on_edit:(CashEntry)->Unit) {
    val time=Instant.ofEpochMilli(entry.occurred_at_ms).atZone(ZoneId.systemDefault())
    val rows=listOf(
        stringResource(R.string.change_amount) to cash_change_text(entry),
        stringResource(R.string.entry_source) to cash_source_label(entry.source_kind),
        stringResource(R.string.entry_account) to account_name,
        stringResource(R.string.currency) to (entry.currency.code+" · "+entry.currency.name),
        stringResource(R.string.record_date) to time.toLocalDate().toString(),
        stringResource(R.string.record_time) to time.format(DateTimeFormatter.ofPattern("HH:mm")),
        stringResource(R.string.note) to entry.note.ifBlank{stringResource(R.string.no_note)})
    RecordDetailLayout(stringResource(R.string.cash_entry_detail),rows) {
        ActionButton(onClick={on_edit(entry)},modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(when(entry.source_kind) {
                "TRADE"->R.string.view_source_trade
                "TERM_OPEN","TERM_CLOSE"->R.string.view_source_deposit
                else->R.string.edit_cash_change
            }))
        }
    }
}
