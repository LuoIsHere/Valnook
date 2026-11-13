package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space inside scrolling content, so its viewport still draws behind system UI/overlays. */
val LocalPageBottomSpace = compositionLocalOf { 0.dp }
val LocalPageTopSpace = compositionLocalOf { 0.dp }
/** Height of the page title bar, for content that moves up into it while scrolling. */
val LocalPageTopBarHeight = compositionLocalOf { 56.dp }

/**
 * Lays the content out [amount] wider on each side than its slot, without moving its neighbours: a row that scrolls
 * sideways inside a padded page reaches the screen edges, and puts the same padding back inside its scroll.
 */
fun Modifier.bleedHorizontally(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = (amount * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
fun pageContentPadding(horizontal: Dp = 16.dp, top: Dp = 8.dp, bottom: Dp = 16.dp): PaddingValues {
    val density = LocalDensity.current
    val pageBottom = LocalPageBottomSpace.current
    val ime = WindowInsets.ime
    val safeDrawing = WindowInsets.safeDrawing
    // Read as the space it gives, which stays 0 while the keyboard moves: pages do not recompose on every frame.
    val overlay by remember(density, pageBottom, ime, safeDrawing) { derivedStateOf {
        if (ime.getBottom(density) > 0) 0.dp
        else maxOf(pageBottom, with(density) { safeDrawing.getBottom(density).toDp() })
    } }
    return PaddingValues(start = horizontal, top = top + LocalPageTopSpace.current,
        end = horizontal, bottom = bottom + overlay)
}
