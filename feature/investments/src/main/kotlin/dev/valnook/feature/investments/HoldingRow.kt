package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.Investment
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.math.RoundingMode

@Composable
internal fun profitColor(sign: Int): Color = when {
    sign > 0 -> if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFF49C996) else Color(0xFF168457)
    sign < 0 -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

private fun number(value: BigDecimal?, digits: Int): String =
    value?.setScale(digits, RoundingMode.HALF_UP)?.toPlainString() ?: "待补全"

private fun signed(value: BigDecimal?, digits: Int): String =
    (if (value?.signum() == 1) "+" else "") + number(value, digits)

@Composable
private fun Metric(primary: String, secondary: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(primary, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End, color = color)
        Text(secondary, style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun HoldingRow(asset: Investment, onOpen: (() -> Unit)? = null) {
    val profit = InvestmentProfitCalculator.fromReadModel(asset)
    val market = number(AssetValuation.marketValue(asset), asset.currency.fraction_digits)
    val price = DecimalRules.format_e8(asset.current_price_e8)
    val cost = profit.average_cost?.setScale(8, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString() ?: "—"
    val quantity = DecimalRules.format_e8(asset.holding_quantity_e8) + " 份"
    val floating = signed(profit.unrealized, asset.currency.fraction_digits)
    val percentage = profit.unrealizedPercent?.let { signed(it, 2) + "%" } ?: "—"
    val color = profitColor(profit.unrealized?.signum() ?: 0)
    val click = if (onOpen == null) Modifier else Modifier.clickable(role = Role.Button, onClick = onOpen)
    BoxWithConstraints(Modifier.fillMaxWidth().then(click).padding(vertical = 8.dp).testTag("holding-${asset.id}")) {
        val fontScale = LocalDensity.current.fontScale
        val stacked = fontScale > 1.3f || maxWidth < 330.dp || maxOf(market.length, price.length, floating.length) > 12
        val name: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(asset.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                InstrumentCodeLine(asset.symbol, asset.currency.code,
                    symbolModifier = Modifier.testTag("holding-symbol-${asset.id}"),
                    currencyModifier = Modifier.testTag("holding-currency-${asset.id}"))
            }
        }
        if (!stacked) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1.3f)) { name() }
            Metric(market, quantity, Modifier.weight(1f))
            Metric(price, cost, Modifier.weight(1f))
            Metric(floating, percentage, Modifier.weight(1f), color)
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            name()
            if (fontScale <= 1.3f) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text("市值 / 数量", style = MaterialTheme.typography.labelSmall)
                    Metric(market, quantity, Modifier.fillMaxWidth()) }
                Column(Modifier.weight(1f)) { Text("现价 / 成本", style = MaterialTheme.typography.labelSmall)
                    Metric(price, cost, Modifier.fillMaxWidth()) }
                Column(Modifier.weight(1f)) { Text("浮动盈亏", style = MaterialTheme.typography.labelSmall)
                    Metric(floating, percentage, Modifier.fillMaxWidth(), color) }
            } else {
                listOf(Triple("市值 / 数量", market, quantity), Triple("现价 / 成本", price, cost),
                    Triple("浮动盈亏", floating, percentage)).forEachIndexed { index, (label, main, sub) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Metric(main, sub, Modifier.weight(1.6f), if (index == 2) color else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HoldingColumns() {
    if (LocalDensity.current.fontScale <= 1.3f) BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 330.dp) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("名称 / 代码" to 1.3f, "市值 / 数量" to 1f, "现价 / 成本" to 1f, "浮动盈亏" to 1f).forEach { (title, weight) ->
                Text(title, Modifier.weight(weight), style = MaterialTheme.typography.labelSmall,
                    textAlign = if (weight > 1f) TextAlign.Start else TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
