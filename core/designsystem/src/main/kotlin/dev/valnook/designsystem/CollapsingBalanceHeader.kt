package dev.valnook.designsystem

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A fling at least this fast (px/s) decides the direction; a slower release goes to the nearer end. */
private const val SETTLE_FLING_VELOCITY = 600f
private val HEADER_SIDE = 16.dp
private val COLLAPSED_FONT = 15.sp
private val BALANCE_FONT = 30.sp
private val CHART_HEIGHT = 120.dp
/**
 * Share of the collapse in which the details above the balance fade out before the balance starts to move. The balance
 * rises through where they were, so moving any earlier would draw it over text still showing.
 */
private const val DETAILS_FADE = .2f

/**
 * One progress value for a balance header: 0 is fully open, 1 is collapsed. Finger movement collapses the header
 * before the list scrolls, and the header opens again only once the list is back at its top. Nothing rests halfway.
 */
@Stable
class HeaderCollapse internal constructor(private val saved: MutableFloatState, private val scope: CoroutineScope) :
    NestedScrollConnection {
    var rangePx by mutableFloatStateOf(0f)
        internal set
    val progress: Float get() = saved.floatValue
    /** The eased value for what moves on its own: the balance and the opening details. */
    val eased: Float get() = progress.let { it * it * (3f - 2f * it) }
    /** How far the balance has travelled into the title bar, eased: it starts once the details have faded. */
    val travel: Float get() = ((progress - DETAILS_FADE) / (1f - DETAILS_FADE)).coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
    /** The details above the balance: fully shown when open, gone by the time the balance starts to move. */
    val detailsAlpha: Float get() = (1f - progress / DETAILS_FADE).coerceIn(0f, 1f)
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

/** Kept across configuration changes per [key], so each account or sub-account remembers how far it was closed. */
@Composable fun rememberHeaderCollapse(key: Any): HeaderCollapse {
    val saved = rememberSaveable(key) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    return remember(saved, scope) { HeaderCollapse(saved, scope) }
}

/** For the opaque parts below the header: dragging them moves the header too. */
@Composable fun Modifier.headerDrag(collapse: HeaderCollapse): Modifier = draggable(
    rememberDraggableState { collapse.dragBy(it) }, Orientation.Vertical, onDragStopped = { collapse.settle(it) })

/**
 * A balance with its trend. Collapsing takes the layout height away without clipping or resizing anything: the
 * balance moves into the title bar, ending [collapsedEndInset] from the end so the bar's buttons stay clear, and the
 * chart keeps its size (resizing would rebuild and squash its path) while the opaque content below slides over it.
 * [top] and [trailing] fade first and are gone before the balance moves, so the balance never crosses them; the chart
 * fades a little later. [onTitleReserve] tells the bar how much room to leave.
 */
@Composable fun CollapsingBalanceHeader(collapse: HeaderCollapse, balance: String, balanceColor: Color,
    onTitleReserve: (Int) -> Unit, modifier: Modifier = Modifier, balanceModifier: Modifier = Modifier,
    collapsedEndInset: Dp = 44.dp, trailing: (@Composable () -> Unit)? = null,
    top: @Composable ColumnScope.(fading: Modifier) -> Unit,
    chart: @Composable ColumnScope.(Modifier) -> Unit) {
    val density = LocalDensity.current
    val barHeight = with(density) { LocalPageTopBarHeight.current.toPx() }
    val baseStyle = MaterialTheme.typography.headlineMedium.copy(fontSize = BALANCE_FONT, lineHeight = 38.sp, fontFeatureSettings = "tnum")
    var fontSize by remember { mutableStateOf(BALANCE_FONT) }
    var textSize by remember { mutableStateOf(IntSize.Zero) }
    var rowTop by remember { mutableFloatStateOf(0f) }
    var textTop by remember { mutableFloatStateOf(0f) }
    var headerWidth by remember { mutableIntStateOf(0) }
    fun compactScale(): Float {
        val width = textSize.width.toFloat()
        if (width <= 0f) return 1f
        return minOf(with(density) { COLLAPSED_FONT.toPx() / fontSize.toPx() }, headerWidth * .5f / width, 1f)
    }
    // The title makes room for the balance as it arrives.
    val reserve by remember { derivedStateOf {
        ((textSize.width * compactScale() + with(density) { 8.dp.toPx() }) * collapse.travel).roundToInt()
    } }
    val reserveCallback by rememberUpdatedState(onTitleReserve)
    LaunchedEffect(Unit) { snapshotFlow { reserve }.collect { reserveCallback(it) } }
    DisposableEffect(Unit) { onDispose { reserveCallback(0) } }
    Column(modifier.fillMaxWidth().headerDrag(collapse)
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(placeable.width, (placeable.height * (1f - collapse.progress)).roundToInt()) { placeable.place(0, 0) }
        }
        .onSizeChanged { collapse.rangePx = it.height.toFloat(); headerWidth = it.width }
        .padding(horizontal = HEADER_SIDE)) {
        val fading = Modifier.graphicsLayer { alpha = collapse.detailsAlpha }
        Spacer(Modifier.height(4.dp))
        top(fading)
        Row(Modifier.fillMaxWidth().onPlaced { rowTop = it.positionInParent().y },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BalanceText(balance, balanceColor, baseStyle, Modifier.weight(1f, fill = false)
                .onPlaced { textTop = it.positionInParent().y }
                .onSizeChanged { textSize = it }
                .graphicsLayer {
                    val eased = collapse.travel
                    val compact = compactScale()
                    val scale = 1f + (compact - 1f) * eased
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                    val endX = headerWidth - collapsedEndInset.toPx() - textSize.width * compact
                    val endY = -barHeight / 2f - textSize.height * compact / 2f
                    translationX = (endX - HEADER_SIDE.toPx()) * eased
                    translationY = (endY - rowTop - textTop) * eased
                }.then(balanceModifier)) { fontSize = it }
            // Gone with the details once the header is mostly closed; the bar keeps only its own buttons.
            if (trailing != null) {
                // Only crossing the half-way point changes this, not every frame of the collapse.
                val hidden by remember(collapse) { derivedStateOf { collapse.progress >= .5f } }
                Box(fading.padding(start = 8.dp).then(if (hidden) Modifier.clearAndSetSemantics {} else Modifier)) { trailing() }
            }
        }
        Spacer(Modifier.height(4.dp))
        chart(Modifier.graphicsLayer {
            alpha = (1f - collapse.progress * 3f).coerceAtLeast(0f)
            translationY = -collapse.progress * 30.dp.toPx()
        }.height(CHART_HEIGHT))
    }
}

@Composable private fun BalanceText(text: String, color: Color, style: TextStyle, modifier: Modifier, onFontSize: (TextUnit) -> Unit) {
    Text(text, modifier, color = color, style = style, maxLines = 1,
        autoSize = TextAutoSize.StepBased(14.sp, BALANCE_FONT, .5.sp),
        onTextLayout = { onFontSize(it.layoutInput.style.fontSize) })
}
