package dev.valnook.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Space inside scrolling content, so its viewport still draws behind system UI/overlays. */
val LocalPageBottomSpace = compositionLocalOf { 0.dp }

@Composable
fun pageContentPadding(horizontal: Dp = 16.dp, top: Dp = 8.dp, bottom: Dp = 16.dp): PaddingValues {
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val overlay = if (keyboardOpen) 0.dp else LocalPageBottomSpace.current
    return PaddingValues(start = horizontal, top = top, end = horizontal, bottom = bottom + overlay)
}
