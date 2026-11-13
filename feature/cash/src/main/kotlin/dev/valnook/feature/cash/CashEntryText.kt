package dev.valnook.feature.cash

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashEntryBill
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
    CashSource.BILL->R.string.source_bill
    CashSource.BILL_TRANSFER_IN->R.string.source_bill_transfer_in
    CashSource.BILL_REMOVED->R.string.source_bill_removed
})

/**
 * A bill's entry as the bills page titles the bill: its note, else "category-subcategory", or a transfer's
 * "from → to"; a refund or reimbursement as "Refund · its bill's name". Null when nothing is left to name it by, so
 * the caller falls back to the source.
 */
@Composable internal fun cash_bill_title(bill: CashEntryBill): String? {
    bill.settlement?.let { type ->
        val label = stringResource(if (type == dev.valnook.domain.model.BillType.REFUND) dev.valnook.feature.cash.R.string.cash_bill_refund
            else dev.valnook.feature.cash.R.string.cash_bill_reimbursement)
        val name = bill.parentNote.trim().takeIf { it.isNotEmpty() }?.replace(WHITESPACE_RUN, " ")
            ?: bill.categoryTitle(LocalConfiguration.current.locales[0].language != "zh") ?: return label
        return "$label · $name"
    }
    bill.note.trim().takeIf { it.isNotEmpty() }?.let { return it.replace(WHITESPACE_RUN, " ") }
    if (!bill.transfer) return bill.categoryTitle(LocalConfiguration.current.locales[0].language != "zh")
    val external = stringResource(dev.valnook.feature.cash.R.string.cash_bill_external)
    val deleted = stringResource(dev.valnook.feature.cash.R.string.cash_bill_deleted_account)
    fun side(name: String?, outside: Boolean) = if (outside) bill.counterparty.ifBlank { external } else name ?: deleted
    return side(bill.from, bill.fromExternal) + " → " + side(bill.to, bill.toExternal)
}

private val WHITESPACE_RUN = Regex("\\s+")
