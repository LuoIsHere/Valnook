package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.Trade
import dev.valnook.domain.money.DecimalRules as Decimal

@Composable internal fun TradeHistoryItem(trade:Trade,on_open:()->Unit) {
    OutlinedCard(onClick=on_open,modifier=Modifier.fillMaxWidth().testTag("trade-record-${trade.id}")) {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.trade_summary,stringResource(if(trade.direction==Direction.BUY)R.string.buy else R.string.sell),
                Decimal.format_display(trade.amount_minor,trade.currency.fraction_digits),trade.currency.code),
                style=MaterialTheme.typography.titleLarge.copy(fontFeatureSettings="tnum"))
            Text(stringResource(R.string.trade_quantity_price,Decimal.format_e8(trade.quantity_e8),
                Decimal.format_e8(trade.execution_price_e8),trade.currency.code),style=MaterialTheme.typography.bodyMedium,
                color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
