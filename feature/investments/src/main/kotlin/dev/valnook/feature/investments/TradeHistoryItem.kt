package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.RecordListItem
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.Trade
import dev.valnook.domain.money.DecimalRules as Decimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable internal fun TradeHistoryItem(trade:Trade,showDivider:Boolean=true,on_open:()->Unit) {
    val time = Instant.ofEpochMilli(trade.occurred_at_ms).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    val directionColor = profitColor(if (trade.direction == Direction.BUY) 1 else -1)
    val settledAmount = tradeAmountWithFee(trade.direction, trade.amount_minor, trade.fee_minor)
    RecordListItem(Modifier.testTag("trade-record-${trade.id}"), on_open, showDivider) {
        Text(time, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(if(trade.direction==Direction.BUY)R.string.buy else R.string.sell) + " · " +
                    Decimal.format_display(settledAmount, trade.currency.fraction_digits) + " " + trade.currency.code,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = directionColor)
                Text(stringResource(R.string.execution_price) + " " + Decimal.format_e8(trade.execution_price_e8) +
                    " " + trade.currency.code, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.quantity) + " " + Decimal.format_e8(trade.quantity_e8),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"))
                Text(stringResource(R.string.trade_fee) + " " +
                    Decimal.format_display(trade.fee_minor, trade.currency.fraction_digits) + " " + trade.currency.code,
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
