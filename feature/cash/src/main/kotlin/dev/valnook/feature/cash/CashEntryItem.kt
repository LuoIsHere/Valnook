package dev.valnook.feature.cash

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.money.DecimalRules

internal fun cash_change_text(entry:CashEntry)=
    (if(entry.delta_minor>0)"+" else "")+DecimalRules.format_display(entry.delta_minor,entry.currency.fraction_digits)+" "+entry.currency.code

@Composable internal fun cash_source_label(kind:String)=stringResource(when(kind) {
    "TRADE"->R.string.source_trade
    "TERM_OPEN"->R.string.source_deposit_open
    "TERM_CLOSE"->R.string.source_deposit_close
    else->R.string.manual_balance_change
})

@Composable internal fun CashEntryItem(entry:CashEntry,on_open:()->Unit) {
    OutlinedCard(onClick=on_open,modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.xs)) {
            Text(cash_change_text(entry),style=MaterialTheme.typography.titleLarge.copy(fontFeatureSettings="tnum"))
            Text(cash_source_label(entry.source_kind),style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
