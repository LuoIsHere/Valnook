package dev.valnook.feature.accounts

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.valnook.designsystem.LocalPageTitle
import dev.valnook.designsystem.LocalPageTopBarHeight
import dev.valnook.designsystem.TrendChart
import dev.valnook.domain.calculation.CurvePoint
import dev.valnook.domain.calculation.MonotoneCurve
import dev.valnook.domain.model.StatisticsPoint
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle as DateTextStyle
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A fling at least this fast (px/s) decides the direction; a slower release goes to the nearer end. */
private const val SETTLE_FLING_VELOCITY = 600f
private val HEADER_SIDE = 16.dp
/** The title bar's edit button and its margin, plus a small gap. */
private val COLLAPSED_END_INSET = 44.dp
private val COLLAPSED_FONT = 15.sp
private val BALANCE_FONT = 30.sp
private val CHART_HEIGHT = 120.dp

/**
 * One progress value for the header: 0 is fully open, 1 is collapsed. Finger movement collapses the header before the
 * list scrolls, and the header opens again only once the list is back at its top. Nothing rests halfway.
 */
@Stable
internal class HeaderCollapse(private val saved: MutableFloatState, private val scope: CoroutineScope) : NestedScrollConnection {
    var rangePx by mutableFloatStateOf(0f)
    val progress: Float get() = saved.floatValue
    /** The eased value for what moves on its own: the balance and the opening details. */
    val eased: Float get() = progress.let { it * it * (3f - 2f * it) }
    private var settling: Job? = null

    /** Returns how much of the finger movement it used. A negative [deltaY] moves up and collapses. */
    fun dragBy(deltaY: Float): Float {
        if (rangePx <= 0f) return 0f
        settling?.cancel()
        val before = saved.floatValue
        saved.floatValue = (before - deltaY / rangePx).coerceIn(0f, 1f)
        return (before - saved.floatValue) * rangePx
    }

    fun settle(velocityY: Float) {
        val current = saved.floatValue
        if (current <= 0f || current >= 1f) return
        val target = when {
            velocityY <= -SETTLE_FLING_VELOCITY -> 1f
            velocityY >= SETTLE_FLING_VELOCITY -> 0f
            current < .5f -> 0f
            else -> 1f
        }
        settling?.cancel()
        settling = scope.launch {
            animate(current, target, animationSpec = tween(200, easing = FastOutSlowInEasing)) { value, _ -> saved.floatValue = value }
        }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, dragBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f) Offset(0f, dragBy(available.y)) else Offset.Zero

    override suspend fun onPreFling(available: Velocity): Velocity {
        if (progress <= 0f || progress >= 1f) return Velocity.Zero
        settle(available.y)
        return available
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle(available.y)
        return Velocity.Zero
    }
}

@Composable internal fun rememberHeaderCollapse(accountId: Long): HeaderCollapse {
    val saved = rememberSaveable(accountId) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    return remember(saved, scope) { HeaderCollapse(saved, scope) }
}

@Composable internal fun Modifier.headerDrag(collapse: HeaderCollapse): Modifier = draggable(
    rememberDraggableState { collapse.dragBy(it) }, Orientation.Vertical, onDragStopped = { collapse.settle(it) })

/**
 * The balance and the trend. Collapsing takes the layout height away without clipping or resizing anything: the balance
 * moves into the title bar, and the chart keeps its size (resizing would rebuild and squash its path) while the tabs
 * slide over it. The month labels go first, then the line and its area fade while rising a little.
 */
@Composable internal fun AccountDetailHeader(collapse: HeaderCollapse, name: String, total: String, totalColor: Color,
    points: List<StatisticsPoint>, accountId: Long, pointText: (BigDecimal?) -> String, onTitleReserve: (Int) -> Unit) {
    val density = LocalDensity.current
    // A finger on the chart shows that day's value in the balance's place.
    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }
    val selected = selectedIndex?.let(points::getOrNull)
    val locale = LocalConfiguration.current.locales[0]
    val selectedDate = selected?.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    val shownText = if (selected != null) pointText(selected.value) else total
    val shownColor = if (selected?.value != null) { if (selected.value!!.signum() < 0) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurface } else totalColor
    val barHeight = with(density) { LocalPageTopBarHeight.current.toPx() }
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(fontSize = BALANCE_FONT, lineHeight = 38.sp, fontFeatureSettings = "tnum")
    var fontSize by remember { mutableStateOf(BALANCE_FONT) }
    var textSize by remember { mutableStateOf(IntSize.Zero) }
    var textTop by remember { mutableFloatStateOf(0f) }
    var headerWidth by remember { mutableIntStateOf(0) }
    fun compactScale(): Float {
        val width = textSize.width.toFloat()
        if (width <= 0f) return 1f
        return minOf(with(density) { COLLAPSED_FONT.toPx() / fontSize.toPx() }, headerWidth * .5f / width, 1f)
    }
    // The title makes room for the balance as it arrives.
    val reserve by remember { derivedStateOf {
        ((textSize.width * compactScale() + with(density) { 8.dp.toPx() }) * collapse.eased).roundToInt()
    } }
    LaunchedEffect(Unit) { snapshotFlow { reserve }.collect(onTitleReserve) }
    DisposableEffect(Unit) { onDispose { onTitleReserve(0) } }
    Column(Modifier.fillMaxWidth().headerDrag(collapse)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(placeable.width, (placeable.height * (1f - collapse.progress)).roundToInt()) { placeable.place(0, 0) }
        }
        .onSizeChanged { collapse.rangePx = it.height.toFloat(); headerWidth = it.width }
        .padding(horizontal = HEADER_SIDE).testTag("account-detail-header")) {
        val fading = Modifier.graphicsLayer { alpha = (1f - collapse.eased * 2.2f).coerceAtLeast(0f) }
        Spacer(Modifier.height(4.dp))
        if (LocalPageTitle.current != name) Text(name, fading, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(when {
            selected == null -> stringResource(R.string.accounts_total_assets)
            selected.complete -> selectedDate.orEmpty()
            else -> stringResource(R.string.account_trend_incomplete, selectedDate.orEmpty())
        }, fading.testTag("account-detail-total-label"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BalanceText(shownText, shownColor, baseStyle, Modifier
            .onPlaced { textTop = it.positionInParent().y }
            .onSizeChanged { textSize = it }
            .graphicsLayer {
                val eased = collapse.eased
                val compact = compactScale()
                val scale = 1f + (compact - 1f) * eased
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0f)
                val endX = headerWidth - COLLAPSED_END_INSET.toPx() - textSize.width * compact
                val endY = -barHeight / 2f - textSize.height * compact / 2f
                translationX = (endX - HEADER_SIDE.toPx()) * eased
                translationY = (endY - textTop) * eased
            }.testTag("account-detail-total")) { fontSize = it }
        Spacer(Modifier.height(4.dp))
        AccountTrendChart(accountId, points, selectedIndex, { index ->
            // Ignored while closing: the chart is on its way out and may sit under the finger.
            selectedIndex = index.takeIf { collapse.progress <= 0f }
        }, (selectedDate?.let { "$it · " } ?: "") + shownText, labelAlpha = { (1f - collapse.progress * 7f).coerceAtLeast(0f) },
            modifier = Modifier.graphicsLayer {
                alpha = (1f - collapse.progress * 3f).coerceAtLeast(0f)
                translationY = -collapse.progress * 30.dp.toPx()
            }.height(CHART_HEIGHT).testTag("account-trend-chart"))
    }
}

@Composable private fun BalanceText(text: String, color: Color, style: TextStyle, modifier: Modifier, onFontSize: (androidx.compose.ui.unit.TextUnit) -> Unit) {
    Text(text, modifier, color = color, style = style, maxLines = 1,
        autoSize = TextAutoSize.StepBased(14.sp, BALANCE_FONT, .5.sp),
        onTextLayout = { onFontSize(it.layoutInput.style.fontSize) })
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
