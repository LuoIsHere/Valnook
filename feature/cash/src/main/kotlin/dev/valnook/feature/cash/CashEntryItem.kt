package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.RecordListItem
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.money.DecimalRules
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun cash_change_text(entry:CashEntry)=
    (if(entry.delta_minor>0)"+" else "")+DecimalRules.format_display(entry.delta_minor,entry.currency.fraction_digits)+" "+entry.currency.code

@Composable internal fun cash_source_label(kind:CashSource)=stringResource(when(kind) {
    CashSource.TRADE->R.string.source_trade
    CashSource.TERM_OPEN->R.string.source_deposit_open
    CashSource.TERM_CLOSE->R.string.source_deposit_close
    CashSource.CASH_SET->R.string.manual_balance_change
})

/**
 * The second line starts with the time. A note says more than the source label, so it follows the time when present;
 * the detail page shows both. A long note shortens on its own, so the time always stays whole.
 */
@Composable fun CashEntryItem(entry:CashEntry,showDivider:Boolean=true,on_open:()->Unit) {
    val note=entry.note.trim().replace(Regex("\\s+")," ")
    val time=Instant.ofEpochMilli(entry.occurred_at_ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
    val style=MaterialTheme.typography.bodySmall
    val color=MaterialTheme.colorScheme.onSurfaceVariant
    RecordListItem(onClick=on_open,showDivider=showDivider) {
        Text(cash_change_text(entry),style=MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings="tnum"))
        Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            // Latin digits and a Chinese note use fonts of different heights, so they line up by baseline.
            Text("$time ·",Modifier.alignByBaseline(),style=style,color=color,maxLines=1)
            Text(note.ifEmpty{cash_source_label(entry.source)},Modifier.alignByBaseline().weight(1f,fill=false),style=style,
                color=color,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
    }
}
