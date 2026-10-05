package dev.valnook.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector4D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TwoWayConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt

private val capsuleBoundsConverter = TwoWayConverter<Rect, AnimationVector4D>(
    convertToVector = { AnimationVector4D(it.left, it.top, it.right, it.bottom) },
    convertFromVector = { Rect(it.v1, it.v2, it.v3, it.v4) },
)

/** One moving indicator; option hit areas and accessibility selection stay stationary. */
@Composable
fun <T> CapsuleChoiceRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionModifier: (T) -> Modifier = { Modifier },
    controlHeight: Dp = 38.dp,
    optionWeight: (T) -> Float = { 1f },
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorInset: Dp = 3.dp,
    spacing: Dp = 0.dp,
    label: @Composable RowScope.(T) -> Unit,
) {
    val capsuleShape = RoundedCornerShape(percent = 50)
    val density = LocalDensity.current
    val height = maxOf(controlHeight, 48.dp, (24f * density.fontScale + 16f).dp)
    val inset = with(density) { indicatorInset.toPx() }
    val bounds = remember { mutableStateMapOf<T, Rect>() }
    val geometry = options.map { it to bounds[it] }
    val target = bounds[selectedOption]?.takeIf { selectedOption in options }
        ?.let { Rect(it.left + inset, it.top + inset, it.right - inset, it.bottom - inset) }
    Box(modifier.height(height).clip(capsuleShape).background(containerColor)) {
        if (target != null && target.width > 0 && target.height > 0) {
            val position = remember { Animatable(target, capsuleBoundsConverter) }
            var previousGeometry by remember { mutableStateOf(geometry) }
            LaunchedEffect(target, geometry) {
                if (previousGeometry != geometry) {
                    previousGeometry = geometry
                    position.snapTo(target)
                } else position.animateTo(target, tween(220, easing = FastOutSlowInEasing))
            }
            Box(Modifier.align(AbsoluteAlignment.TopLeft)
                .absoluteOffset { IntOffset(position.value.left.roundToInt(), position.value.top.roundToInt()) }
                .layout { measurable, _ ->
                    val rect = position.value
                    val placeable = measurable.measure(Constraints.fixed(
                        rect.width.roundToInt().coerceAtLeast(0), rect.height.roundToInt().coerceAtLeast(0)))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .background(MaterialTheme.colorScheme.secondaryContainer, capsuleShape)
                .testTag("capsule-indicator"))
        }
        Row(Modifier.fillMaxSize().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
            options.forEach { option -> key(option) {
                val isSelected = option == selectedOption
                val contentColor by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(120), label = "capsule-content")
                Box(Modifier.weight(optionWeight(option)).fillMaxHeight()
                    .onGloballyPositioned { coordinates ->
                        val origin = coordinates.positionInParent()
                        bounds[option] = Rect(origin.x, origin.y,
                            origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                    }
                    .then(optionModifier(option)).clip(capsuleShape)
                    .selectable(selected = isSelected, onClick = { onOptionSelected(option) }, role = Role.Tab),
                    contentAlignment = Alignment.Center) {
                    CompositionLocalProvider(LocalContentColor provides contentColor) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 3.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            label(option)
                        }
                    }
                }
            } }
        }
    }
}
