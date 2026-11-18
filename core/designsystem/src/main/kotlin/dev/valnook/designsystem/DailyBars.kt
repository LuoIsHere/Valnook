package dev.valnook.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import dev.valnook.core.designsystem.R
import kotlin.math.abs
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/**
 * One bar per slot (a day), growing from a shared baseline. [values] are heights from 0 to 1; null leaves the slot
 * empty (a day still to come). [labels] put text under chosen slots by index.
 *
 * A tap picks the slot under the finger and [onSelect] reports it; tapping the picked slot again, or a slot still to
 * come, reports null. Dragging sideways picks whichever slot the finger is over (never past the last one with a
 * value), and the pick stays when the finger lifts. A drag that starts upright is left to the page to scroll. With a
 * slot picked, the other bars fade back.
 *
 * The bars grow in once per [animationKey], the first time they are on screen. New values (another month) move each
 * bar from its old height to its new one instead.
 */
@Composable
fun DailyBars(
    values: List<Float?>,
    labels: List<Pair<Int, String>>,
    selection: Int?,
    onSelect: (Int?) -> Unit,
    color: Color,
    description: String,
    modifier: Modifier = Modifier,
    animationKey: String = "bars",
) {
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelSize = with(density) { MaterialTheme.typography.labelSmall.fontSize.toPx() }
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER } }
    var visible by remember { mutableStateOf(false) }
    var played by rememberSaveable(animationKey) { mutableStateOf(false) }
    val reveal = remember(animationKey) { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(animationKey) {
        if (!played) {
            snapshotFlow { visible }.first { it }
            played = true
            reveal.animateTo(1f, tween(650, easing = CubicBezierEasing(.25f, .1f, .25f, 1f)))
        }
    }
    // New values start from the heights on screen, so a change mid-way carries on from where the bars are.
    var from by remember { mutableStateOf(values) }
    var to by remember { mutableStateOf(values) }
    val morph = remember { Animatable(1f) }
    fun height(index: Int, progress: Float): Float? {
        val start = from.getOrNull(index)
        val end = to.getOrNull(index)
        if (start == null && end == null) return null
        return (start ?: 0f) + ((end ?: 0f) - (start ?: 0f)) * progress
    }
    LaunchedEffect(values) {
        if (values == to) return@LaunchedEffect
        val progress = morph.value
        from = List(maxOf(from.size, to.size)) { height(it, progress) }
        to = values
        morph.snapTo(0f)
        morph.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
    }
    val fade by animateFloatAsState(if (selection != null) 0.35f else 1f, tween(160), label = "bars-fade")
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSelection by rememberUpdatedState(selection)
    val count = values.size
    val lastWithValue = values.indexOfLast { it != null }
    val previousLabel = stringResource(R.string.bars_previous_day)
    val nextLabel = stringResource(R.string.bars_next_day)
    Box(modifier.semantics {
            contentDescription = description
            if (lastWithValue >= 0) customActions = listOf(
                CustomAccessibilityAction(previousLabel) {
                    val at = currentSelection ?: lastWithValue
                    if (currentSelection == null || at > 0) { currentOnSelect((if (currentSelection == null) at else at - 1)); true } else false
                },
                CustomAccessibilityAction(nextLabel) {
                    val at = currentSelection ?: return@CustomAccessibilityAction false
                    if (at < lastWithValue) { currentOnSelect(at + 1); true } else false
                })
        }
        .onGloballyPositioned { if (it.boundsInWindow().height > 0f) visible = true }
        .pointerInput(values) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (count == 0) return@awaitEachGesture
                fun slotAt(x: Float) = (x / (size.width.toFloat() / count)).toInt().coerceIn(0, count - 1)
                val before = currentSelection
                var horizontal = false
                var vertical = false
                var picked: Int? = null
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        // Lifted without dragging: a tap, which picks the slot or lets the picked one go.
                        if (!horizontal) {
                            val index = slotAt(down.position.x)
                            currentOnSelect(if (index == before || values.getOrNull(index) == null) null else index)
                        }
                        break
                    }
                    val delta = change.position - down.position
                    // The first clear direction decides, as on the trend chart: sideways picks days, upright scrolls.
                    if (!horizontal && !vertical && maxOf(abs(delta.x), abs(delta.y)) > viewConfiguration.touchSlop) {
                        horizontal = abs(delta.x) > abs(delta.y)
                        vertical = !horizontal
                    }
                    if (vertical || (change.isConsumed && !horizontal)) break
                    if (horizontal && lastWithValue >= 0) {
                        change.consume()
                        val index = slotAt(change.position.x).coerceAtMost(lastWithValue)
                        if (index != picked) { picked = index; currentOnSelect(index) }
                    }
                }
            }
        }) {
        Canvas(Modifier.fillMaxSize()) {
            if (count == 0) return@Canvas
            val labelSpace = labelSize + 8.dp.toPx()
            val bottom = size.height - labelSpace
            val top = 4.dp.toPx()
            val slot = size.width / count
            // Thin bars with at least 2dp of surface between neighbours.
            val width = minOf(slot * 0.62f, 10.dp.toPx(), slot - 2.dp.toPx()).coerceAtLeast(1f)
            val radius = minOf(width / 2f, 4.dp.toPx())
            drawLine(baseline, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())
            val progress = morph.value
            for (index in 0 until maxOf(count, from.size)) {
                val value = height(index, progress) ?: continue
                if (index >= count) continue
                val h = (bottom - top) * value.coerceIn(0f, 1f) * reveal.value
                if (h < 0.5f) continue
                val left = slot * index + (slot - width) / 2f
                val path = Path().apply {
                    addRoundRect(RoundRect(left, bottom - h, left + width, bottom,
                        topLeftCornerRadius = CornerRadius(radius), topRightCornerRadius = CornerRadius(radius)))
                }
                drawPath(path, color.copy(alpha = if (selection == null || selection == index) 1f else fade))
            }
            labelPaint.textSize = labelSize
            labelPaint.color = labelColor.toArgb()
            labels.forEach { (index, text) ->
                if (index in 0 until count) drawContext.canvas.nativeCanvas.drawText(text,
                    slot * index + slot / 2f, size.height - 2.dp.toPx(), labelPaint)
            }
        }
    }
}
