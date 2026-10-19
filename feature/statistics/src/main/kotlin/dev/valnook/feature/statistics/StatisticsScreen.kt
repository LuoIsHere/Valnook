package dev.valnook.feature.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.LocalGainLossPalette
import dev.valnook.designsystem.TrendChart
import androidx.compose.ui.platform.LocalConfiguration
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.CapsuleChoiceRow
import dev.valnook.designsystem.pageContentPadding
import dev.valnook.domain.calculation.CurvePoint
import dev.valnook.domain.calculation.MonotoneCurve
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
fun StatisticsScreen(vm: StatisticsViewModel, summary: @Composable () -> Unit = {}) {
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
    LazyColumn(Modifier.fillMaxSize().testTag("statistics-list"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.lg)) {
        item("asset-summary") { summary() }
        if (state.failed) item("statistics-retry") {
            TextButton(vm::refresh) { Text(stringResource(R.string.statistics_load_failed)) }
        }
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
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("statistics-${chart.metric.name.lowercase()}"),
            verticalArrangement=Arrangement.spacedBy(Space.sm)) {
            androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.SpaceBetween,verticalArrangement=Arrangement.spacedBy(Space.sm)) {
                Text(title,style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier=Modifier.padding(end=16.dp,top=8.dp))
                CapsuleChoiceRow(options=StatisticsGranularity.entries,selectedOption=chart.period.granularity,
                    onOptionSelected=onGranularity,modifier=Modifier.width(136.dp)
                        .testTag("statistics-granularity-${chart.metric.name.lowercase()}"),
                    optionModifier={ Modifier.testTag("statistics-granularity-${chart.metric.name.lowercase()}-${it.name.lowercase()}") }) { value ->
                    Text(stringResource(if(value==StatisticsGranularity.DAILY) R.string.statistics_daily else R.string.statistics_monthly),
                        maxLines=1,style=MaterialTheme.typography.labelMedium)
                }
            }
            // Never label the old curve with the newly requested period during an asynchronous load.
            // 切换期间时不能把旧曲线冒充新期间；保留固定区域，等待对应数据。
            val series=chart.series?.takeIf { it.period==chart.period }
            key(chart.metric,chart.period) {
                InteractiveChart(series ?: StatisticsSeries(chart.metric,chart.period,chart.series?.currency,emptyList()),title)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically) {
                IconButton(previous,modifier=Modifier.semantics { contentDescription=previousLabel }) { Text("‹",style=MaterialTheme.typography.titleLarge) }
                Text(periodLabel(chart.period),style=MaterialTheme.typography.labelLarge)
                IconButton(next,enabled=canGoNext,modifier=Modifier.semantics { contentDescription=nextLabel }) { Text("›",style=MaterialTheme.typography.titleLarge) }
                Box(Modifier.size(18.dp)) { if(loading) CircularProgressIndicator(Modifier.fillMaxSize().testTag("statistics-loading-${chart.metric.name.lowercase()}"),strokeWidth=2.dp) }
            }
        }
    }
}

@Composable
internal fun InteractiveChart(series: StatisticsSeries, title: String, showChange: Boolean = true,
    valueTag: String = "statistics-value-${series.metric.name.lowercase()}", chartTag: String = "statistics-chart-${series.metric.name.lowercase()}") {
    var selectedIndex by remember(series) { mutableStateOf<Int?>(null) }
    val selected=if(selectedIndex!=null)series.points.getOrNull(selectedIndex!!) else series.points.lastOrNull { !it.future }
    val locale=LocalConfiguration.current.locales[0]
    val palette=LocalGainLossPalette.current
    val dateText=selected?.date?.format(DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM).withLocale(locale)).orEmpty()
    val valueText=money(selected?.value,series.currency)
    val change=selected?.change
    val percent=selected?.changePercent?.let {
        (if(it.signum()>0)"+"else "")+NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits=2; maximumFractionDigits=2 }.format(it)+"%"
    }?:"—"
    Column(Modifier.fillMaxWidth().testTag("statistics-readout-${series.metric.name.lowercase()}")) {
        Text(valueText,style=MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings="tnum"),
            modifier=Modifier.testTag(valueTag))
        Text(dateText.ifEmpty { "—" },style=MaterialTheme.typography.bodySmall.copy(lineHeight=24.sp),maxLines=1,
            color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=2.dp)
                .heightIn(min=with(LocalDensity.current){24.sp.toDp()}))
        if (showChange) {
        Text(stringResource(if(series.period.granularity==StatisticsGranularity.DAILY) R.string.statistics_day_change else R.string.statistics_month_change),
            style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
        Text(money(change,series.currency,signed=true)+" ("+percent+")",style=MaterialTheme.typography.titleMedium.copy(fontFeatureSettings="tnum"),
            color=when(change?.signum()) { 1->palette.gain; -1->palette.loss; else->palette.neutral },
            modifier=Modifier.testTag("statistics-change-${series.metric.name.lowercase()}"))
        }
    }
    val normalized=remember(series.points) {
        val values=series.points.mapNotNull { it.value }
        val low=values.minOrNull()?:BigDecimal.ZERO
        val high=values.maxOrNull()?:BigDecimal.ZERO
        val range=high-low
        series.points.map { point -> point.value?.let { if(range.signum()==0).5f else
            .08f+.84f*(1f-it.subtract(low).divide(range,12,RoundingMode.HALF_UP).toFloat()) } }
    }
    val labels=remember(series.period,series.points,locale) { sparseXAxisLabels(series).map { (index,_) ->
        val date=series.points[index].date
        index to if(series.period.granularity==StatisticsGranularity.MONTHLY)date.month.getDisplayName(java.time.format.TextStyle.SHORT,locale)
            else date.dayOfMonth.toString()
    } }
    val futureExtensionFrom=series.points.indexOfFirst { it.future }.takeIf { it>0 }?.minus(1)?.takeIf {
        series.points[it].reliable && series.points[it].value!=null
    }
    TrendChart(normalized,labels,selectedIndex,{selectedIndex=it},curvePath={ group ->
        Path().apply { moveTo(group.first().x,group.first().y)
            MonotoneCurve.segments(group.map { CurvePoint(it.x,it.y) }).forEach { s ->
                cubicTo(s.control1.x,s.control1.y,s.control2.x,s.control2.y,s.end.x,s.end.y)
            }
        }
    },description=stringResource(R.string.statistics_chart_description,title,periodLabel(series.period)),
        readout="$dateText, $valueText",animationKey="${series.metric}-${series.period}",futureExtensionFrom=futureExtensionFrom,
        modifier=Modifier.padding(top=12.dp).height(if(showChange)192.dp else 172.dp).testTag(chartTag))
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
    val format=NumberFormat.getNumberInstance().apply {
        minimumFractionDigits=currency?.fraction_digits?:2
        maximumFractionDigits=currency?.fraction_digits?:2
        if(this is java.text.DecimalFormat) decimalFormatSymbols=decimalFormatSymbols.apply { groupingSeparator=' ';decimalSeparator='.' }
    }
    return "$prefix${format.format(value)} ${currency?.code.orEmpty()}".trim()
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
