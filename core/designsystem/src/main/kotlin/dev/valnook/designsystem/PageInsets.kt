package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space inside scrolling content, so its viewport still draws behind system UI/overlays. */
val LocalPageBottomSpace = compositionLocalOf { 0.dp }
val LocalPageTopSpace = compositionLocalOf { 0.dp }
/** Height of the page title bar, for content that moves up into it while scrolling. */
val LocalPageTopBarHeight = compositionLocalOf { 56.dp }

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
