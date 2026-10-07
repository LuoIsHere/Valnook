package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import kotlin.math.ceil

/** Dialogs use a denser material; fallback stays opaque when window blur is unavailable. */
internal val LocalGlassDialogBlur = compositionLocalOf<Boolean?> { null }

internal object GlassMaterial {
    val blurRadius = 20.dp
    const val panelAlpha = 0.92f
    const val cardAlpha = 0.92f
}

/** One page drawing, shared by the two overlays; never includes either overlay. */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
    internal var generation by mutableIntStateOf(0)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier =
    onGloballyPositioned { backdrop.origin = it.positionInRoot() }.drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
        // Observe only at the consumer's draw phase, not in composition or this source.
        Snapshot.withoutReadObservation { backdrop.generation++ }
    }

/** Blur the recorded background only. Text and interaction layers are drawn afterwards. */
@Composable
fun GlassSurface(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(20.dp),
    backdrop: GlassBackdrop? = null, tintAlpha: Float = GlassMaterial.cardAlpha,
    content: @Composable BoxScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val dialogBlur = LocalGlassDialogBlur.current
    val alpha = when (dialogBlur) { true -> GlassMaterial.panelAlpha; false -> 1f; null -> tintAlpha }
    val tint = lerp(colors.surfaceContainerLow, Color.White, 0.02f).copy(alpha = alpha)
    val sample = if (backdrop != null) rememberGraphicsLayer() else null
    var origin by remember { mutableStateOf(Offset.Zero) }
    val radius = with(LocalDensity.current) { GlassMaterial.blurRadius.toPx() }
    val blur = remember(radius, backdrop) { if (backdrop != null) BlurEffect(radius, radius, TileMode.Clamp) else null }
    Box(modifier.clip(shape).then(if (backdrop != null)
        Modifier.onGloballyPositioned { origin = it.positionInRoot() } else Modifier)
        .drawWithContent {
            // Keep the material tint and the blurred scene contribution independent.
            // An opaque foundation prevents the unblurred page leaking through overlays.
            if (backdrop != null) drawRect(colors.background)
            drawRect(tint)
            if (backdrop != null && sample != null && backdrop.generation > 0 && size.width > 0 && size.height > 0) {
                val overscan = ceil(radius * 2).toInt()
                val delta = backdrop.origin - origin
                sample.renderEffect = blur
                sample.alpha = 0.28f
                sample.record(size = IntSize(size.width.toInt() + overscan * 2,
                    size.height.toInt() + overscan * 2)) {
                    drawRect(colors.background)
                    translate(delta.x + overscan, delta.y + overscan) { drawLayer(backdrop.layer) }
                }
                translate(-overscan.toFloat(), -overscan.toFloat()) { drawLayer(sample) }
            }
            drawContent()
        }, content = content)
}

/** A grouped surface, intentionally without a live blur layer per scrolling item. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, prominent: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    GlassSurface(modifier.fillMaxWidth(), RoundedCornerShape(if (prominent) 24.dp else 18.dp)) {
        Column(content = content)
    }
}

@Composable
fun SummaryMetric(label: String, value: String, modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = valueColor)
    }
}

@Composable
fun MetricGrid(count: Int, content: @Composable (Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth < 280.dp || LocalDensity.current.fontScale > 1.3f) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (start in 0 until count step columns) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (column in 0 until columns) {
                        Box(Modifier.weight(1f)) { if (start + column < count) content(start + column) }
                    }
                }
            }
        }
    }
}

@Composable
fun HintMessage(message: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (isError) colors.errorContainer.copy(alpha = 0.55f) else colors.surfaceContainerHigh.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp)) {
        Text(message, Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) colors.onErrorContainer else colors.onSurfaceVariant)
    }
}

@Composable
fun PageLoading() {
    Box(Modifier.fillMaxSize().padding(pageContentPadding()), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun PageFailure(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(pageContentPadding()), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HintMessage(message, isError = true)
            ActionButton(onClick = onRetry) { Text(stringResource(R.string.retry_loading)) }
        }
    }
}

/** Set only when the root toolbar already presents this exact form title. */
val LocalPageTitle = compositionLocalOf { "" }
