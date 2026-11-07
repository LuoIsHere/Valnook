package dev.valnook.feature.investments

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.valnook.designsystem.TrendChart
import dev.valnook.domain.calculation.CurvePoint
import dev.valnook.domain.calculation.MonotoneCurve
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.InstrumentPricePoint
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "1.4521": a price as stored, without trailing zeros. */
internal fun priceText(priceE5: Long): String = BigDecimal.valueOf(priceE5, 5).stripTrailingZeros().toPlainString()

/** "10月3日" or "Oct 3". */
internal fun shortDate(atMs: Long, locale: Locale): String = Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale))

/**
 * The points the trend draws, oldest first, and where each sits across it by its time. One price is drawn as a
 * level line from edge to edge, so it is given twice.
 */
internal fun pricePlot(prices: List<InstrumentPricePoint>): Pair<List<InstrumentPricePoint>, List<Float>> {
    // [prices] come newest first; reversed before a stable sort, updates at one moment keep their record order.
    val points = prices.reversed().sortedBy { it.atMs }
    if (points.size == 1) return listOf(points[0], points[0]) to listOf(0f, 1f)
    val start = points.firstOrNull()?.atMs ?: 0L
    val span = (points.lastOrNull()?.atMs ?: 0L) - start
    // Updates at one moment cannot be told apart by time; spread them evenly instead.
    val positions = if (span <= 0L) points.indices.map { it.toFloat() / (points.size - 1).coerceAtLeast(1) }
        else points.map { ((it.atMs - start).toDouble() / span).toFloat() }
    return points to positions
}

/**
 * Only the recorded updates, placed by their dates and joined by a curve. [selected] indexes the plotted points;
 * the header shows the chosen one.
 */
@Composable internal fun InstrumentPriceTrend(points: List<InstrumentPricePoint>, positions: List<Float>, currency: Currency,
    selected: Int?, onSelect: (Int?) -> Unit, labelAlpha: () -> Float, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val values = remember(points) {
        val low = points.minOfOrNull { it.priceE5 } ?: 0L
        val high = points.maxOfOrNull { it.priceE5 } ?: 0L
        points.map { point -> if (high == low) .5f else .08f + .84f * (1f - (point.priceE5 - low).toFloat() / (high - low)) }
    }
    val labels = remember(points, locale) {
        if (points.isEmpty()) emptyList()
        else listOf(0 to shortDate(points.first().atMs, locale)) +
            (if (points.last().atMs != points.first().atMs) listOf(points.lastIndex to shortDate(points.last().atMs, locale)) else emptyList())
    }
    val chosen = (selected ?: points.lastIndex).let(points::getOrNull)
    val readout = chosen?.let { shortDate(it.atMs, locale) + " · " + priceText(it.priceE5) + " " + currency.code }.orEmpty()
    val description = stringResource(R.string.instrument_price_trend) + "; " +
        points.distinct().joinToString("; ") { shortDate(it.atMs, locale) + ": " + priceText(it.priceE5) }
    TrendChart(values, labels, selected, onSelect, curvePath = { group ->
        Path().apply {
            moveTo(group.first().x, group.first().y)
            MonotoneCurve.segments(group.map { CurvePoint(it.x, it.y) }).forEach { s ->
                cubicTo(s.control1.x, s.control1.y, s.control2.x, s.control2.y, s.end.x, s.end.y)
            }
        }
    }, description = description, readout = readout, animationKey = "instrument-price", labelAlpha = labelAlpha,
        positions = positions, modifier = modifier.testTag("instrument-price-trend"))
}
