package dev.valnook.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.LocalGainLossPalette
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.CapsuleChoiceRow
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.calculation.CurvePoint
import dev.valnook.domain.calculation.MonotoneCurve
import dev.valnook.domain.calculation.StatisticsAxis
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatisticsScreen(vm: StatisticsViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showLoading by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) {
        showLoading = false
        if (state.loading) {
            delay(300)
            showLoading = true
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.refresh() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(vm) {
        while (isActive) {
            delay(vm.millisUntilNextLocalDay())
            vm.refresh()
        }
    }
    if (state.failed && state.current == null) {
        Box(Modifier.fillMaxSize().padding(Space.md)) { Text(stringResource(R.string.statistics_load_failed)) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        item { MonthlyChange(state.current?.monthlyChange) }
        StatisticsMetric.entries.forEach { metric ->
            item(metric.name) {
                state.charts[metric]?.let { chart ->
                    StatisticChart(chart, state.today, { vm.setGranularity(metric, it) }, { vm.previous(metric) },
                        { vm.next(metric) },
                        loading = state.loading && showLoading && chart.series?.period != chart.period)
                }
            }
        }
    }
}

@Composable
private fun MonthlyChange(change: MonthlyAssetChange?) {
    val palette = LocalGainLossPalette.current
    val value = change?.value
    Column(Modifier.testTag("statistics-monthly-change"),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.statistics_change_month), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(0.42f))
            Text(if (value == null) "—" else money(value, change.currency, signed = true),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(0.58f),
                color = when (value?.signum()) { 1 -> palette.gain; -1 -> palette.loss; else -> palette.neutral })
        }
        if (value == null) Text(stringResource(R.string.statistics_unknown),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatisticChart(
    chart: ChartUiState,
    today: LocalDate,
    onGranularity: (StatisticsGranularity) -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    loading: Boolean
) {
    val title = metricTitle(chart.metric)
    val canGoNext = chart.period.year < today.year ||
        (chart.period.granularity == StatisticsGranularity.DAILY && chart.period.year == today.year &&
            requireNotNull(chart.period.month) < today.monthValue)
    val previousLabel = stringResource(if (chart.period.granularity == StatisticsGranularity.DAILY)
        R.string.statistics_previous_month else R.string.statistics_previous_year)
    val nextLabel = stringResource(if (chart.period.granularity == StatisticsGranularity.DAILY)
        R.string.statistics_next_month else R.string.statistics_next_year)
    GlassCard {
    Column(Modifier.fillMaxWidth().padding(12.dp).testTag("statistics-${chart.metric.name.lowercase()}"),
        verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            CapsuleChoiceRow(
                options = StatisticsGranularity.entries,
                selectedOption = chart.period.granularity,
                onOptionSelected = onGranularity,
                modifier = Modifier.widthIn(min = 128.dp, max = 144.dp)
                    .testTag("statistics-granularity-${chart.metric.name.lowercase()}"),
                optionModifier = { value ->
                    Modifier.testTag("statistics-granularity-${chart.metric.name.lowercase()}-${value.name.lowercase()}")
                },
            ) { value ->
                Text(
                    if (value == StatisticsGranularity.DAILY) stringResource(R.string.statistics_daily)
                    else stringResource(R.string.statistics_monthly),
                    maxLines = 1,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Row {
                TextButton(previous, modifier = Modifier.semantics { contentDescription = previousLabel }) {
                    Text("‹", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(next, enabled = canGoNext,
                    modifier = Modifier.semantics { contentDescription = nextLabel }) {
                    Text("›", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(periodLabel(chart.period), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f))
            // Keep the slot even when idle: loading must not change the card or list height.
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(
                    modifier = Modifier.fillMaxSize().testTag("statistics-loading-${chart.metric.name.lowercase()}"),
                    strokeWidth = 2.dp,
                )
            }
        }
        chart.series?.let { InteractiveChart(it, today, title) }
    }
    }
}

@Composable
private fun InteractiveChart(series: StatisticsSeries, today: LocalDate, title: String) {
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let(series.points::getOrNull)
        ?: series.points.indexOfLast { it.value != null }.takeIf { it >= 0 }?.let(series.points::get)
    val currentSuffix = if (selected?.date == today) " · ${stringResource(R.string.statistics_current)}" else ""
    val dateText = selected?.let { "${it.date.format(DateTimeFormatter.ISO_LOCAL_DATE)}$currentSuffix" }.orEmpty()
    val valueText = selected?.let { money(it.value, series.currency) }
        ?: stringResource(R.string.statistics_no_data)
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)
            .testTag("statistics-readout-${series.metric.name.lowercase()}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            text = dateText,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valueText,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ChartCanvas(series, selectedIndex, { selectedIndex = it }, { selectedIndex = null }, title)
}

@Composable
private fun ChartCanvas(series: StatisticsSeries, selectedIndex: Int?, select: (Int) -> Unit,
    clearSelection: () -> Unit, title: String) {
    val values = series.points.mapNotNull { it.value }
    val scaleHolder = remember(series.metric, series.period) { arrayOfNulls<AxisScale>(1) }
    val scale = remember(values) {
        StatisticsAxis.scale(values, scaleHolder[0]).also { scaleHolder[0] = it }
    }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val pointColor = MaterialTheme.colorScheme.secondary
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val latest = series.points.indexOfLast { it.value != null }.coerceAtLeast(0)
    val description = stringResource(R.string.statistics_chart_description, title, periodLabel(series.period))
    val selection = selectedIndex ?: latest
    val stateText = series.points.getOrNull(selection)?.let { point ->
        "${point.date.format(DateTimeFormatter.ISO_LOCAL_DATE)}, ${money(point.value, series.currency)}"
    }.orEmpty()
    val previousPoint = stringResource(R.string.statistics_previous_point)
    val nextPoint = stringResource(R.string.statistics_next_point)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        scale?.let { axis ->
            val labels = axisLabels(axis.ticks)
            val labelStyle = MaterialTheme.typography.labelSmall
            val textMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val axisWidth = remember(labels, labelStyle, density) {
                val measured = labels.maxOfOrNull { label ->
                    textMeasurer.measure(label, style = labelStyle, maxLines = 1).size.width
                } ?: 0
                with(density) { measured.toDp() + 2.dp }.coerceIn(24.dp, 56.dp)
            }
            Column(
                modifier = Modifier.width(axisWidth).height(168.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                labels.asReversed().forEach { label ->
                    Text(
                        text = label,
                        style = labelStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Canvas(Modifier.weight(1f).height(192.dp)
            .testTag("statistics-chart-${series.metric.name.lowercase()}").semantics {
        contentDescription = description
        stateDescription = stateText
        customActions = listOf(
            CustomAccessibilityAction(previousPoint) {
                (selection - 1).takeIf { it >= 0 }?.let(select); selection > 0
            },
            CustomAccessibilityAction(nextPoint) {
                (selection + 1).takeIf { it < series.points.size }?.let(select); selection + 1 < series.points.size
            })
    }.pointerInput(series.points) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val choose: (Float) -> Unit = { x ->
                val index = if (series.points.size <= 1) 0 else
                    ((x / size.width) * (series.points.size - 1)).roundToInt().coerceIn(series.points.indices)
                select(index)
            }
            choose(down.position.x)
            var horizontal = false
            var vertical = false
            var pressed = true
            var lastX = down.position.x
            try {
                while (pressed) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    val delta = change.position - down.position
                    lastX = change.position.x
                    if (!horizontal && !vertical &&
                        maxOf(abs(delta.x), abs(delta.y)) > viewConfiguration.touchSlop) {
                        horizontal = abs(delta.x) > abs(delta.y)
                        vertical = !horizontal
                    }
                    if (horizontal) {
                        change.consume()
                        choose(lastX)
                    }
                    pressed = event.changes.any { it.pressed }
                }
            } finally {
                clearSelection()
            }
        }
    }) {
        if (scale == null) return@Canvas
        val top = 10f
        val labelSpace = 24.dp.toPx()
        val bottom = size.height - labelSpace - 12f
        val height = bottom - top
        val range = scale.maximum - scale.minimum
        fun x(index: Int): Float = if (series.points.size <= 1) size.width / 2f
            else size.width * index / series.points.lastIndex
        fun y(value: BigDecimal): Float {
            val fraction = value.subtract(scale.minimum).divide(range, 12, RoundingMode.HALF_UP).toFloat()
            return bottom - fraction * height
        }
        scale.ticks.forEach { tick -> drawLine(gridColor, Offset(0f, y(tick)), Offset(size.width, y(tick)), 1f) }
        val labelPaint = android.graphics.Paint().apply {
            color = axisTextColor
            textSize = 11.sp.toPx()
            isAntiAlias = true
        }
        sparseXAxisLabels(series).forEach { (index, label) ->
            val labelWidth = labelPaint.measureText(label)
            val left = when (index) {
                0 -> 0f
                series.points.lastIndex -> size.width - labelWidth
                else -> x(index) - labelWidth / 2f
            }.coerceIn(0f, (size.width - labelWidth).coerceAtLeast(0f))
            drawContext.canvas.nativeCanvas.drawText(label, left, size.height - 2.dp.toPx(), labelPaint)
        }
        val groups = mutableListOf<MutableList<Pair<Int, BigDecimal>>>()
        series.points.forEachIndexed { index, point ->
            val value = point.value ?: return@forEachIndexed
            if (index == 0 || series.points[index - 1].value == null) groups.add(mutableListOf())
            groups.last().add(index to value)
        }
        groups.forEach { group ->
            if (group.size == 1) drawCircle(lineColor, 4f, Offset(x(group[0].first), y(group[0].second))) else {
                val normalized = group.map { (index, value) -> CurvePoint(x(index), y(value)) }
                val path = Path().apply { moveTo(normalized.first().x, normalized.first().y) }
                MonotoneCurve.segments(normalized).forEach { segment ->
                    path.cubicTo(segment.control1.x, segment.control1.y, segment.control2.x,
                        segment.control2.y, segment.end.x, segment.end.y)
                }
                drawPath(path, lineColor, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
            }
        }
        series.points.getOrNull(selection)?.value?.let { drawCircle(pointColor, 6f, Offset(x(selection), y(it))) }
        }
    }
}

internal fun sparseXAxisLabels(series: StatisticsSeries): List<Pair<Int, String>> {
    if (series.points.isEmpty()) return emptyList()
    val last = series.points.lastIndex
    val indexes = if (series.period.granularity == StatisticsGranularity.DAILY)
        listOf(0, 7, 14, 21, last) else listOf(0, 3, 6, 9, last)
    return indexes.distinct().filter { it in series.points.indices }.map { index ->
        val date = series.points[index].date
        index to if (series.period.granularity == StatisticsGranularity.DAILY)
            date.dayOfMonth.toString() else date.monthValue.toString()
    }
}

@Composable
private fun metricTitle(metric: StatisticsMetric): String = stringResource(when (metric) {
    StatisticsMetric.TOTAL_ASSETS -> R.string.statistics_total
    StatisticsMetric.AVAILABLE_CASH -> R.string.statistics_cash
    StatisticsMetric.INVESTMENT_VALUE -> R.string.statistics_investment
})

private fun periodLabel(period: StatisticsPeriod): String = if (period.granularity == StatisticsGranularity.DAILY)
    "%04d-%02d".format(period.year, period.month) else period.year.toString()

private fun money(value: BigDecimal?, currency: Currency?, signed: Boolean = false): String {
    if (value == null) return "—"
    val prefix = if (signed && value.signum() > 0) "+" else ""
    return "$prefix${NumberFormat.getNumberInstance().format(value)} ${currency?.code.orEmpty()}".trim()
}

internal fun axisLabels(values: List<BigDecimal>): List<String> {
    val formatted = values.map(::compactAxisLabel)
    return formatted.mapIndexed { index, label ->
        if (index == 0 || label != formatted[index - 1]) label else ""
    }
}

private val compactAxisUnits = listOf(
    BigDecimal.ONE to "",
    BigDecimal("1000") to "k",
    BigDecimal("1000000") to "M",
    BigDecimal("1000000000") to "B",
    BigDecimal("1000000000000") to "T",
)

internal fun compactAxisLabel(value: BigDecimal): String {
    val rounded = value.setScale(2, RoundingMode.HALF_UP)
    if (rounded.abs() < BigDecimal("1000")) {
        return if (rounded.signum() == 0) "0" else rounded.stripTrailingZeros().toPlainString()
    }
    var unitIndex = compactAxisUnits.indexOfLast { (divisor, _) -> value.abs() >= divisor }.coerceAtLeast(1)
    var scaled = value.divide(compactAxisUnits[unitIndex].first, 1, RoundingMode.HALF_UP)
    while (scaled.abs() >= BigDecimal("1000") && unitIndex < compactAxisUnits.lastIndex) {
        unitIndex++
        scaled = value.divide(compactAxisUnits[unitIndex].first, 1, RoundingMode.HALF_UP)
    }
    if (scaled.abs() >= BigDecimal("1000")) {
        val exponent = value.precision() - value.scale() - 1
        val scientific = value.movePointLeft(exponent).setScale(1, RoundingMode.HALF_UP)
        return "${scientific.toPlainString()}E$exponent"
    }
    return "${scaled.toPlainString()}${compactAxisUnits[unitIndex].second}"
}
