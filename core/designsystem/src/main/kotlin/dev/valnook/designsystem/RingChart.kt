package dev.valnook.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One part of a ring: [fraction] of the whole circle, drawn in [color]. [key] keeps it the same part across changes. */
data class RingSlice(val key: Any, val fraction: Float, val color: Color)

/**
 * A ring split into [slices], clockwise from the top, with a gap the colour of the surface between neighbours.
 * Fractions should add up to at most 1; whatever is left shows as the bare track.
 *
 * The ring draws itself in once per [animationKey], the first time it is on screen; afterwards new data moves each
 * part from its old size to its new one rather than drawing the ring again. [center] sits in the hole.
 */
@Composable
fun RingChart(
    slices: List<RingSlice>,
    description: String,
    modifier: Modifier = Modifier,
    thickness: Dp = 16.dp,
    animationKey: String = "ring",
    center: @Composable BoxScope.() -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    var visible by remember { mutableStateOf(false) }
    val settled = LocalPageSettled.current
    var played by rememberSaveable(animationKey) { mutableStateOf(false) }
    val reveal = remember(animationKey) { Animatable(if (played) 1f else 0f) }
    LaunchedEffect(animationKey) {
        if (!played) {
            snapshotFlow { visible && settled() }.first { it }
            played = true
            reveal.animateTo(1f, tween(650, easing = CubicBezierEasing(.25f, .1f, .25f, 1f)))
        }
    }
    // Each part keeps its own animated size, so a part that grows, shrinks, arrives or leaves moves on its own.
    val sizes = remember { mutableStateMapOf<Any, Animatable<Float, AnimationVector1D>>()
        .apply { slices.forEach { put(it.key, Animatable(it.fraction)) } } }
    val colors = remember { mutableStateMapOf<Any, Color>().apply { slices.forEach { put(it.key, it.color) } } }
    val order = remember { mutableStateListOf<Any>().apply { addAll(slices.map { it.key }) } }
    SideEffect { slices.forEach { if (colors[it.key] != it.color) colors[it.key] = it.color } }
    LaunchedEffect(slices) {
        val targets = slices.associate { it.key to it.fraction }
        slices.forEach { if (it.key !in sizes) sizes[it.key] = Animatable(0f) }
        // Parts on their way out stay at the end until they have shrunk away.
        val leaving = order.filter { it !in targets }
        order.clear(); order.addAll(slices.map { it.key } + leaving)
        coroutineScope {
            sizes.toList().forEach { (key, size) ->
                launch { size.animateTo(targets[key] ?: 0f, tween(450, easing = FastOutSlowInEasing)) }
            }
        }
        leaving.forEach { sizes.remove(it); colors.remove(it) }
        order.removeAll(leaving)
    }
    Box(modifier.aspectRatio(1f).semantics { contentDescription = description }
        .onGloballyPositioned { if (it.boundsInWindow().height > 0f) visible = true },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = thickness.toPx()
            val radius = (size.minDimension - stroke) / 2f
            val topLeft = Offset(size.width / 2f - radius, size.height / 2f - radius)
            val arcSize = Size(radius * 2, radius * 2)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
            // The gap is a fixed width at the ring's middle, turned into degrees for this radius.
            val gap = Math.toDegrees((2.dp.toPx() / radius).toDouble()).toFloat()
            val parts = order.mapNotNull { key -> sizes[key]?.value?.takeIf { it > 0f }?.let { key to it } }
            val split = parts.size > 1
            var start = -90f
            val end = -90f + 360f * reveal.value
            parts.forEach { (key, fraction) ->
                val sweep = 360f * fraction
                val from = start + if (split) gap / 2 else 0f
                val to = minOf(start + sweep - if (split) gap / 2 else 0f, end)
                if (to > from) drawArc(colors[key] ?: track, from, to - from, false, topLeft, arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Butt))
                start += sweep
            }
        }
        center()
    }
}
