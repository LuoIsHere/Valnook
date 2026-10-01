package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.valnook.designsystem.*
import dev.valnook.core.designsystem.R
import dev.valnook.domain.model.InvestmentProfit
import java.math.BigDecimal
import java.math.RoundingMode

@Composable fun ProfitSummaryCard(profit:InvestmentProfit?,currency:String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.md),verticalArrangement=Arrangement.spacedBy(Space.sm)) {
            Text(stringResource(R.string.profit_summary),style=MaterialTheme.typography.titleLarge)
            if(profit==null)CircularProgressIndicator()
            else {
                ProfitMetric(stringResource(R.string.average_buy_cost),profit.average_cost?.setScale(8,RoundingMode.HALF_UP)?.stripTrailingZeros(),currency,false)
                ProfitMetric(stringResource(R.string.realized_profit),profit.realized,currency,true)
                ProfitMetric(stringResource(R.string.unrealized_profit),profit.unrealized,currency,true)
                if(!profit.cost_complete)Text(stringResource(R.string.cost_incomplete),color=MaterialTheme.colorScheme.error)
                else if(!profit.chronology_valid)Text(stringResource(R.string.profit_order_invalid),color=MaterialTheme.colorScheme.error)
                else if(profit.average_cost==null)Text(stringResource(R.string.no_cost_yet))
            }
            Text(stringResource(R.string.profit_rule),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable private fun ProfitMetric(label:String,value:BigDecimal?,currency:String,signed:Boolean) {
    Column(verticalArrangement=Arrangement.spacedBy(Space.xs)) {
        Text(label,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let{(if(signed&&it.signum()>0)"+" else "")+it.toPlainString()+" "+currency} ?: "—",
            style=MaterialTheme.typography.titleLarge.copy(fontFeatureSettings="tnum"))
    }
}
