package dev.valnook.feature.cash

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.money.DecimalRules

internal fun cash_change_text(entry:CashEntry)=
    (if(entry.delta_minor>0)"+" else "")+DecimalRules.format_display(entry.delta_minor,entry.currency.fraction_digits)+" "+entry.currency.code

/** Cash left by a deleted instrument's trade reads as its buy or sale, told apart by the direction of the change. */
@Composable internal fun cash_source_label(entry:CashEntry)=stringResource(when(entry.source) {
    CashSource.TRADE->R.string.source_trade
    CashSource.TERM_OPEN->R.string.source_deposit_open
    CashSource.TERM_CLOSE->R.string.source_deposit_close
    CashSource.CASH_SET->R.string.manual_balance_change
    CashSource.TRADE_REMOVED->if(entry.delta_minor<0)R.string.source_trade_removed_buy else R.string.source_trade_removed_sell
    CashSource.DIVIDEND->R.string.source_dividend
    CashSource.DIVIDEND_REMOVED->R.string.source_dividend_removed
})
