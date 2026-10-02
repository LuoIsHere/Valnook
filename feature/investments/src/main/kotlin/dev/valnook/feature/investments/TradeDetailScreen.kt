package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as Decimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun TradeDetailScreen(vm:TradeDetailViewModel,account_name:String,
    on_edit:(Investment,Trade)->Unit,on_delete:(Investment,Trade)->Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when(val current=state) {
        TradeDetailState.Loading->Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}
        TradeDetailState.Missing->Box(Modifier.padding(Space.lg)){EmptyState(stringResource(R.string.trade_unavailable))}
        TradeDetailState.Failed->Column(Modifier.padding(Space.lg),verticalArrangement=Arrangement.spacedBy(Space.md)) {
            Text(stringResource(R.string.record_load_error))
            ActionButton(onClick=vm::retry){Text(stringResource(R.string.retry_load))}
        }
        is TradeDetailState.Ready->TradeDetailContent(current.asset,current.trade,account_name,
            {on_edit(current.asset,current.trade)},{on_delete(current.asset,current.trade)})
    }
}

@Composable fun TradeDetailContent(asset:Investment,trade:Trade,account_name:String,on_edit:()->Unit,on_delete:()->Unit) {
    val time=Instant.ofEpochMilli(trade.occurred_at_ms).atZone(ZoneId.systemDefault())
    val settledAmount=tradeAmountWithFee(trade.direction,trade.amount_minor,trade.fee_minor)
    val rows=listOf(
        stringResource(R.string.entry_account) to account_name,
        stringResource(R.string.record_investment) to listOf(asset.name,asset.symbol).filter{it.isNotBlank()}.joinToString(" · "),
        stringResource(R.string.trade_direction) to stringResource(if(trade.direction==Direction.BUY)R.string.buy else R.string.sell),
        stringResource(R.string.quantity) to Decimal.format_e8(trade.quantity_e8),
        stringResource(R.string.execution_price) to (Decimal.format_e8(trade.execution_price_e8)+" "+trade.currency.code),
        stringResource(R.string.trade_amount) to (Decimal.format_display(settledAmount,trade.currency.fraction_digits)+" "+trade.currency.code),
        stringResource(R.string.trade_fee) to (Decimal.format_display(trade.fee_minor,trade.currency.fraction_digits)+" "+trade.currency.code),
        stringResource(R.string.currency) to trade.currency.code,
        stringResource(R.string.trade_date) to time.toLocalDate().toString(),
        stringResource(R.string.record_time) to time.format(DateTimeFormatter.ofPattern("HH:mm")),
        stringResource(R.string.record_cash_link) to stringResource(if(trade.cash_linked)R.string.linked else R.string.unlinked))
    RecordDetailLayout(stringResource(R.string.trade_detail_title),rows) {
        ActionButton(onClick=on_edit,modifier=Modifier.fillMaxWidth()){Text(stringResource(R.string.edit_trade))}
        ActionButton(onClick=on_delete,modifier=Modifier.fillMaxWidth(),destructive=true){Text(stringResource(R.string.delete_trade))}
    }
}
