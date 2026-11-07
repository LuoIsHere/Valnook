package dev.valnook.feature.accounts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.CollapsingBalanceHeader
import dev.valnook.designsystem.HeaderCollapse
import dev.valnook.designsystem.LocalPageTitle
import dev.valnook.designsystem.TrendChart
import dev.valnook.domain.calculation.CurvePoint
import dev.valnook.domain.calculation.MonotoneCurve
import dev.valnook.domain.model.StatisticsPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle

/** The title bar's edit button and its margin, plus a small gap. */
private val COLLAPSED_END_INSET = 44.dp

/**
 * The balance and the trend, collapsing as in [CollapsingBalanceHeader]. The month labels go first, then the line and
 * its area fade while rising a little.
 */
@Composable internal fun AccountDetailHeader(collapse: HeaderCollapse, name: String, total: String, totalColor: Color,
    points: List<StatisticsPoint>, accountId: Long, pointText: (BigDecimal?) -> String, onTitleReserve: (Int) -> Unit) {
    // A finger on the chart shows that day's value in the balance's place.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let(points::getOrNull)
    val locale = LocalConfiguration.current.locales[0]
    val selectedDate = selected?.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val shownText = if (selected != null) pointText(selected.value) else total
    val shownColor = if (selected?.value != null) { if (selected.value!!.signum() < 0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface } else totalColor
    CollapsingBalanceHeader(collapse, shownText, shownColor, onTitleReserve,
        Modifier.testTag("account-detail-header"), Modifier.testTag("account-detail-total"), COLLAPSED_END_INSET,
        top = { fading ->
            if (LocalPageTitle.current != name) Text(name, fading, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                selected == null -> stringResource(R.string.accounts_total_assets)
                selected.complete -> selectedDate.orEmpty()
                else -> stringResource(R.string.account_trend_incomplete, selectedDate.orEmpty())
            }, fading.testTag("account-detail-total-label"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }) { chartModifier ->
        AccountTrendChart(accountId, points, selectedIndex, { index ->
            // Ignored while closing: the chart is on its way out and may sit under the finger.
            selectedIndex = index.takeIf { collapse.progress <= 0f }
        }, (selectedDate?.let { "$it · " } ?: "") + shownText, labelAlpha = { (1f - collapse.progress * 7f).coerceAtLeast(0f) },
            modifier = chartModifier.testTag("account-trend-chart"))
    }
}

/** Same drawing as the statistics page, with the sub-account trend's month labels. */
@Composable private fun AccountTrendChart(accountId: Long, points: List<StatisticsPoint>, selected: Int?, onSelect: (Int?) -> Unit,
    readout: String, labelAlpha: () -> Float, modifier: Modifier) {
    val normalized = remember(points) {
        val values = points.mapNotNull { it.value }
        val low = values.minOrNull() ?: BigDecimal.ZERO
        val high = values.maxOrNull() ?: BigDecimal.ZERO
        val range = high - low
        points.map { point -> point.value?.let { value -> if (range.signum() == 0) .5f else
            .08f + .84f * (1f - value.subtract(low).divide(range, 12, RoundingMode.HALF_UP).toFloat()) } }
    }
    val locale = LocalConfiguration.current.locales[0]
    val labels = remember(points, locale) { points.mapIndexedNotNull { index, point ->
        if (index == 0 || point.date.dayOfMonth == 1) index to point.date.month.getDisplayName(DateTextStyle.SHORT, locale) else null
    } }
    TrendChart(normalized, labels, selected, onSelect, curvePath = { group ->
        Path().apply {
            moveTo(group.first().x, group.first().y)
            MonotoneCurve.segments(group.map { CurvePoint(it.x, it.y) }).forEach { s ->
                cubicTo(s.control1.x, s.control1.y, s.control2.x, s.control2.y, s.end.x, s.end.y)
            }
        }
    }, description = stringResource(R.string.account_trend_description), readout = readout, modifier = modifier,
        animationKey = "account-$accountId", labelAlpha = labelAlpha)
}
