package dev.valnook.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp

private val CAPSULE_PADDING = 4.dp
private val TAB_SPACING = 4.dp

/**
 * The bottom navigation's look: pages as icons with their names below, on a translucent capsule, with the chosen
 * page on a capsule that slides between pages. Shared by the real bar and its preview so the two always match.
 *
 * [collapse] folds it towards the start: 0 is the whole bar, 1 is a one-tab capsule holding only [selected]. The tabs
 * keep their full-width places and slide together so the chosen one ends at the start; the others fade out. Past
 * halfway the capsule is one button that calls [onExpand] and reads as [expandLabel].
 */
@Composable
fun <T> NavigationCapsule(items: List<T>, selected: T, onSelect: (T) -> Unit,
    icon: (T) -> Int, label: @Composable (T) -> String,
    modifier: Modifier = Modifier, backdrop: GlassBackdrop? = null, optionModifier: (T) -> Modifier = { Modifier },
    collapse: Float = 0f, onExpand: () -> Unit = {}, expandLabel: String = "") {
    val progress = collapse.coerceIn(0f, 1f)
    val shape = RoundedCornerShape(50)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val full = maxWidth
        val count = items.size.coerceAtLeast(1)
        val tab = (full - CAPSULE_PADDING * 2 - TAB_SPACING * (count - 1)) / count
        val chosen = items.indexOf(selected).coerceAtLeast(0)
        val shift = (tab + TAB_SPACING) * chosen * progress
        val width = lerp(full, tab + CAPSULE_PADDING * 2, progress)
        val folded = progress >= .5f
        GlassSurface(shape = shape, backdrop = backdrop,
            modifier = Modifier.align(Alignment.CenterStart).width(width).clip(shape).testTag("navigation-capsule")) {
            Box(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).requiredWidth(full)
                .graphicsLayer { translationX = (if (rtl) shift else -shift).toPx() }
                .testTag("navigation-tabs")
                // Folded, the tabs stay laid out under the button but are not read out on their own.
                .then(if (folded) Modifier.semantics { hideFromAccessibility() } else Modifier)) {
                CapsuleChoiceRow(items, selected, onSelect,
                    modifier = Modifier.padding(CAPSULE_PADDING), optionModifier = optionModifier,
                    controlHeight = 56.dp, containerColor = Color.Transparent,
                    indicatorInset = 0.dp, spacing = TAB_SPACING) { item ->
                    val style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp,
                        fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal)
                    // The other tabs are gone a little before the capsule is down to one tab.
                    Column(Modifier.graphicsLayer { if (item != selected) alpha = (1f - progress * 1.6f).coerceIn(0f, 1f) },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        FaIcon(icon(item), null, Modifier.size(20.dp))
                        Text(label(item), maxLines = 1, style = style,
                            // Tabs share the width equally. A name that does not fit shrinks instead of being cut off.
                            autoSize = TextAutoSize.StepBased(6.sp, style.fontSize, .25.sp))
                    }
                }
            }
            // Folded, the whole capsule opens the bar again instead of choosing a page.
            if (folded) Box(Modifier.matchParentSize().testTag("navigation-expand")
                .clickable(role = Role.Button, onClick = onExpand)
                .clearAndSetSemantics {
                    contentDescription = expandLabel
                    role = Role.Button
                    onClick { onExpand(); true }
                })
        }
    }
}
