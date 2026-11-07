package dev.valnook.feature.cash

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.money.DecimalRules

internal fun cash_change_text(entry:CashEntry)=
    (if(entry.delta_minor>0)"+" else "")+DecimalRules.format_display(entry.delta_minor,entry.currency.fraction_digits)+" "+entry.currency.code

@Composable internal fun cash_source_label(kind:CashSource)=stringResource(when(kind) {
    CashSource.TRADE->R.string.source_trade
    CashSource.TERM_OPEN->R.string.source_deposit_open
    CashSource.TERM_CLOSE->R.string.source_deposit_close
    CashSource.CASH_SET->R.string.manual_balance_change
})
