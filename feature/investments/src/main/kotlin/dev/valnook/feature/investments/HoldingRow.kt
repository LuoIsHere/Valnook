package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.Investment
import dev.valnook.domain.money.DecimalRules
import dev.valnook.designsystem.LocalGainLossPalette
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun profitColor(sign: Int): Color = when {
    sign > 0 -> LocalGainLossPalette.current.gain
    sign < 0 -> LocalGainLossPalette.current.loss
    else -> LocalGainLossPalette.current.neutral
}

private fun number(value: BigDecimal?, digits: Int): String =
    value?.setScale(digits, RoundingMode.HALF_UP)?.toPlainString() ?: "—"

private fun signed(value: BigDecimal?, digits: Int): String =
    (if (value?.signum() == 1) "+" else "") + number(value, digits)

@Composable
private fun Metric(primary: String, secondary: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(primary, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End, color = color)
        Text(secondary, style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HoldingRow(asset: Investment, onOpen: (() -> Unit)? = null) {
    val profit = InvestmentProfitCalculator.fromReadModel(asset)
    val market = number(AssetValuation.marketValue(asset), asset.currency.fraction_digits)
    val price = DecimalRules.format_e8(asset.current_price_e8)
    val cost = profit.average_cost?.setScale(5, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "—"
    val quantity = stringResource(R.string.investment_units, DecimalRules.format_e8(asset.holding_quantity_e8))
    val floating = signed(profit.unrealized, asset.currency.fraction_digits)
    val percentage = profit.unrealizedPercent?.let { signed(it, 2) + "%" } ?: "—"
    val color = profitColor(profit.unrealized?.signum() ?: 0)
    val click = if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onOpen)
    Row(Modifier.fillMaxWidth().then(click).padding(vertical = 6.dp).testTag("holding-${asset.id}"),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1.55f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(asset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            InstrumentCodeLine(asset.symbol, asset.currency.code,
                symbolModifier = Modifier.testTag("holding-symbol-${asset.id}"),
                currencyModifier = Modifier.testTag("holding-currency-${asset.id}"))
        }
        Metric(market, quantity, Modifier.weight(1f))
        Metric(price, cost, Modifier.weight(1f))
        Metric(floating, percentage, Modifier.weight(1f), color)
    }
}

@Composable
internal fun HoldingColumns() {
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(stringResource(R.string.investment_name_symbol) to 1.55f,
            stringResource(R.string.investment_value_quantity) to 1f,
            stringResource(R.string.investment_price_cost) to 1f,
            stringResource(R.string.investment_unrealized) to 1f).forEach { (title, weight) ->
            Text(title, Modifier.weight(weight), style = MaterialTheme.typography.labelSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = if (weight > 1f) TextAlign.Start else TextAlign.End,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun HoldingTable(assets: List<Investment>, onOpen: ((Investment) -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val needsScroll = LocalDensity.current.fontScale > 1.3f || maxWidth < 320.dp
        val tableWidth = if (needsScroll) 520.dp else maxWidth
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(tableWidth)) {
                HoldingColumns()
                assets.forEachIndexed { index, asset ->
                    HoldingRow(asset, onOpen?.let { callback -> { callback(asset) } })
                    if (index < assets.lastIndex)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                }
            }
        }
    }
}
