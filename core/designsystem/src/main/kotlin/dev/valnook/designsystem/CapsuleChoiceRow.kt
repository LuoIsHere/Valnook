package dev.valnook.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** Compact single-choice control that follows the floating navigation capsule style. */
@Composable
fun <T> CapsuleChoiceRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionModifier: (T) -> Modifier = { Modifier },
    controlHeight: Dp = 38.dp,
    optionWeight: (T) -> Float = { 1f },
    label: @Composable RowScope.(T) -> Unit,
) {
    val capsuleShape = RoundedCornerShape(percent = 50)
    Box(modifier = modifier.height(controlHeight)) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(controlHeight).align(Alignment.Center),
            shape = capsuleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {}
        Row(
            modifier = Modifier.fillMaxSize().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            options.forEach { option ->
                val isSelected = option == selectedOption
                val containerColor = animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    animationSpec = tween(durationMillis = 120),
                    label = "capsule-container",
                )
                val contentColor = animateColorAsState(
                    targetValue = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    animationSpec = tween(durationMillis = 120),
                    label = "capsule-content",
                )
                Box(
                    modifier = Modifier.weight(optionWeight(option)).fillMaxHeight()
                        .then(optionModifier(option))
                        .clip(capsuleShape)
                        .selectable(
                            selected = isSelected,
                            onClick = { onOptionSelected(option) },
                            role = Role.Tab,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.padding(horizontal = 3.dp).fillMaxWidth().height(controlHeight - 6.dp),
                        shape = capsuleShape,
                        color = containerColor.value,
                        contentColor = contentColor.value,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) { label(option) }
                    }
                }
            }
        }
    }
}
