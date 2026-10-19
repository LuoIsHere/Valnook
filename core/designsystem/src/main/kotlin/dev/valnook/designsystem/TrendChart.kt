package dev.valnook.designsystem

import android.graphics.Rect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.valnook.core.designsystem.R
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first

data class TrendPalette(val line: Color, val fill: Color, val muted: Color, val cursor: Color, val separator: Color)
val LocalTrendPalette = staticCompositionLocalOf {
    TrendPalette(Color(0xFFB88714), Color(0xFFE5B83D), Color(0xFFA1A6AE),
        Color(0xFF737983).copy(alpha=.75f), Color(0xFF817763).copy(alpha=.22f))
}

/** Normalized geometry only. Financial values and their meaning stay in the feature/domain layers. */
@Composable
fun TrendChart(
    values: List<Float?>,
    labels: List<Pair<Int, String>>,
    selection: Int?,
    onSelect: (Int?) -> Unit,
    curvePath: (List<Offset>) -> Path,
    description: String,
    readout: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    animationKey: String = "trend",
    futureExtensionFrom: Int? = null,
) {
    val palette = LocalTrendPalette.current
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val view = LocalView.current
    val previousLabel = stringResource(R.string.trend_previous_point)
    val nextLabel = stringResource(R.string.trend_next_point)
    val latestLabel = stringResource(R.string.trend_latest_point)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var visible by remember { mutableStateOf(false) }
    var played by rememberSaveable(animationKey) { mutableStateOf(false) }
    val reveal = remember(animationKey) { Animatable(if (played || compact) 1f else 0f) }
    var interrupted by remember(animationKey) { mutableStateOf(false) }
    var fingerFraction by remember(animationKey) { mutableStateOf<Float?>(null) }
    val ready = values.any { it != null }
    val extensionIndex = futureExtensionFrom?.takeIf { index ->
        index in values.indices && index < values.lastIndex && values[index] != null && values.drop(index+1).all { it==null }
    }
    val selectableLast = extensionIndex ?: values.lastIndex
    LaunchedEffect(ready, animationKey) {
        if (ready && !played) {
            snapshotFlow { visible }.first { it }
            played = true
            reveal.animateTo(1f, tween(650, easing=CubicBezierEasing(.25f, .1f, .25f, 1f)))
        }
    }
    LaunchedEffect(interrupted) {
        if (interrupted) { played = true; reveal.snapTo(1f) }
    }
    LaunchedEffect(values) { fingerFraction = null; currentOnSelect(null) }
    val inset = with(density) { 5.dp.toPx() }
    val labelSpace = with(density) { if (compact) 0f else 27.dp.toPx() }
    val plotWidth = (canvasSize.width - 2 * inset).coerceAtLeast(1f)
    val bottom = (canvasSize.height - labelSpace - inset).coerceAtLeast(inset)
    fun x(index: Int) = if (values.size <= 1) inset + plotWidth / 2f else inset + plotWidth * index / values.lastIndex
    fun point(index: Int, value: Float) = Offset(x(index), inset + (bottom-inset)*value.coerceIn(0f,1f))
    // Paths are rebuilt for data/size changes, never for each animation frame or finger movement.
    // 路径仅随数据和尺寸重建，入场和触摸只改变裁切范围。
    val groups = remember(values, canvasSize, density) {
        val result = mutableListOf<List<Offset>>()
        var run = mutableListOf<Offset>()
        values.forEachIndexed { index, value ->
            if (value == null) { if (run.isNotEmpty()) result += run; run = mutableListOf() }
            else run += point(index, value)
        }
        if (run.isNotEmpty()) result += run
        result
    }
    val paths = remember(groups) { groups.filter { it.size > 1 }.map { group ->
        val line = curvePath(group)
        val area = Path().apply { addPath(line); lineTo(group.last().x,bottom); lineTo(group.first().x,bottom); close() }
        line to area
    } }
    val gesture = if (compact) Modifier else Modifier.pointerInput(values, animationKey, extensionIndex) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (values.isNotEmpty()) {
                interrupted = true
                fun choose(position: Float) {
                    val fraction = ((position-inset)/plotWidth).coerceIn(0f,1f)
                    fingerFraction = fraction
                    currentOnSelect((fraction*values.lastIndex).roundToInt().coerceAtMost(selectableLast))
                }
                choose(down.position.x)
                var horizontal = false
                var vertical = false
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position-down.position
                        if (!horizontal && !vertical && maxOf(abs(delta.x),abs(delta.y)) > viewConfiguration.touchSlop) {
                            horizontal = abs(delta.x)>abs(delta.y)
                            vertical = !horizontal
                        }
                        if (vertical || (change.isConsumed && !horizontal)) break
                        if (horizontal) { change.consume(); choose(change.position.x) }
                    }
                } finally { fingerFraction = null; currentOnSelect(null) }
            }
        }
    }
    Canvas(modifier.fillMaxWidth().onSizeChanged { canvasSize=it }
        .onGloballyPositioned { coordinates ->
            val window = Rect(); view.getWindowVisibleDisplayFrame(window)
            val bounds = coordinates.boundsInWindow()
            visible = bounds.bottom > window.top && bounds.top < window.bottom && bounds.width > 0f && bounds.height > 0f
        }.semantics {
            contentDescription = description
            stateDescription = readout
            if (!compact && values.isNotEmpty()) {
                val selected = selection ?: values.indexOfLast { it != null }.coerceAtLeast(0)
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) { if(selected>0) { currentOnSelect(selected-1); true } else false },
                    CustomAccessibilityAction(nextLabel) { if(selected<selectableLast) { currentOnSelect(selected+1); true } else false },
                    CustomAccessibilityAction(latestLabel) { currentOnSelect(null); true })
            }
        }.then(gesture)) {
        val split = fingerFraction?.let { inset+plotWidth*it } ?: selection?.let(::x)
        val stroke = Stroke(if(compact) 1.8.dp.toPx() else 2.dp.toPx(), cap=StrokeCap.Round)
        val revealRight = if(interrupted) size.width else inset+plotWidth*reveal.value
        // A shared reveal clip includes BOTH the line and area; month dividers cannot appear early.
        // 同一个入场裁切包含曲线、底部面积和月份分隔线，防止面积提前铺满。
        clipRect(right=revealRight) {
            fun drawSeries(color: Color, areaColor: Color, opacity: Float) {
                paths.forEach { (line, area) ->
                    if(!compact) drawPath(area, Brush.verticalGradient(listOf(areaColor.copy(alpha=opacity),areaColor.copy(alpha=0f)),inset,bottom))
                    drawPath(line,color,style=stroke)
                }
                groups.filter { it.size==1 }.forEach { drawCircle(color,2.5.dp.toPx(),it.first()) }
            }
            if(split==null) drawSeries(palette.line,palette.fill,.22f)
            else {
                clipRect(left=split) { drawSeries(palette.muted,palette.muted,.07f) }
                clipRect(right=split) { drawSeries(palette.line,palette.fill,.22f) }
            }
            // Future extension is a visual reference only: no area and no synthetic future samples.
            // 未来虚线仅为当前值的视觉参考，不填充面积、不生成未来账面数据。
            extensionIndex?.let { index ->
                val start=point(index,requireNotNull(values[index]))
                drawLine(palette.muted.copy(alpha=.8f),start,Offset(x(values.lastIndex),start.y),1.5.dp.toPx(),
                    pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),4.dp.toPx())))
            }
            if(!compact) labels.forEach { (index,_) -> values.getOrNull(index)?.let { value ->
                val p=point(index,value)
                drawLine(palette.separator,Offset(p.x,bottom),p,.75.dp.toPx())
            } }
            val dotIndex = selection ?: values.indexOfLast { it!=null }
            values.getOrNull(dotIndex)?.let { value ->
                val p=point(dotIndex,value)
                if(selection!=null || reveal.value>=.999f || interrupted) {
                    drawCircle(palette.line,if(selection==null)2.5.dp.toPx()else 3.8.dp.toPx(),p)
                }
            }
        }
        if(split!=null && !compact) drawLine(palette.cursor,Offset(split,inset),Offset(split,bottom),1.dp.toPx())
        if(!compact) {
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=axisColor.toArgb(); textSize=11.sp.toPx()
            }
            var previousRight=Float.NEGATIVE_INFINITY
            labels.forEach { (index,label) ->
                val width=paint.measureText(label)
                val left=(x(index)-width/2).coerceIn(0f,(size.width-width).coerceAtLeast(0f))
                if(left>=previousRight+8.dp.toPx()) {
                    drawContext.canvas.nativeCanvas.drawText(label,left,size.height-2.dp.toPx(),paint)
                    previousRight=left+width
                }
            }
        }
    }
}
