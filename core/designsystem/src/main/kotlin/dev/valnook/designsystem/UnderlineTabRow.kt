package dev.valnook.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * Text tabs with one underline that slides between them. Tabs keep their natural width, and a name that does not fit
 * shrinks instead of being cut off.
 */
@Composable
fun <T> UnderlineTabRow(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    optionModifier: (T) -> Modifier = { Modifier },
) {
    val density = LocalDensity.current
    val height = maxOf(48.dp, (24f * density.fontScale + 16f).dp)
    val bounds = remember { mutableStateMapOf<T, Rect>() }
    val geometry = options.map { it to bounds[it] }
    val target = bounds[selected]?.takeIf { selected in options }
    val indicatorColor = MaterialTheme.colorScheme.primary
    val style = MaterialTheme.typography.labelLarge
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxSize().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                options.forEach { option -> key(option) {
                    val isSelected = option == selected
                    val color by animateColorAsState(
                        if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        tween(120), label = "tab-content")
                    val text = label(option)
                    Box(Modifier.weight(text.length.coerceAtLeast(1).toFloat(), fill = false).fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            val origin = coordinates.positionInParent()
                            bounds[option] = Rect(origin.x, origin.y,
                                origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                        }
                        .then(optionModifier(option))
                        .selectable(selected = isSelected, onClick = { onSelected(option) }, role = Role.Tab),
                        contentAlignment = Alignment.Center) {
                        Text(text, color = color, style = style, maxLines = 1,
                            autoSize = TextAutoSize.StepBased(6.sp, style.fontSize, .25.sp))
                    }
                } }
            }
            if (target != null && target.width > 0) {
                val position = remember { Animatable(target, capsuleBoundsConverter) }
                var previousGeometry by remember { mutableStateOf(geometry) }
                LaunchedEffect(target, geometry) {
                    if (previousGeometry != geometry) {
                        previousGeometry = geometry
                        position.snapTo(target)
                    } else position.animateTo(target, tween(220, easing = FastOutSlowInEasing))
                }
                Box(Modifier.align(AbsoluteAlignment.BottomLeft)
                    .absoluteOffset { IntOffset(position.value.left.roundToInt(), 0) }
                    .layout { measurable, _ ->
                        val placeable = measurable.measure(Constraints.fixed(
                            position.value.width.roundToInt().coerceAtLeast(0), 2.dp.roundToPx()))
                        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                    }
                    .background(indicatorColor)
                    .testTag("underline-indicator"))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
