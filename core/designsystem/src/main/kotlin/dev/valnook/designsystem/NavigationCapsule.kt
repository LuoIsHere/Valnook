package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The bottom navigation's look: pages as icons with their names below, on a translucent capsule, with the chosen
 * page on a capsule that slides between pages. Shared by the real bar and its preview so the two always match.
 */
@Composable
fun <T> NavigationCapsule(items: List<T>, selected: T, onSelect: (T) -> Unit,
    icon: (T) -> Int, label: @Composable (T) -> String,
    modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null, optionModifier: (T) -> Modifier = { Modifier }) {
    GlassSurface(shape = RoundedCornerShape(50), backdrop = backdrop, modifier = modifier.fillMaxWidth()) {
        CapsuleChoiceRow(items, selected, onSelect,
            modifier = Modifier.padding(4.dp), optionModifier = optionModifier,
            controlHeight = 56.dp, containerColor = Color.Transparent,
            indicatorInset = 0.dp, spacing = 4.dp) { item ->
            val style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp,
                fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                FaIcon(icon(item), null, Modifier.size(20.dp))
                Text(label(item), maxLines = 1, style = style,
                    // Tabs share the width equally. A name that does not fit shrinks instead of being cut off.
                    autoSize = TextAutoSize.StepBased(6.sp, style.fontSize, .25.sp))
            }
        }
    }
}
