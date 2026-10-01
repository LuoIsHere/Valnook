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
fun pageContentPadding(horizontal: Dp = 16.dp, vertical: Dp = 16.dp): PaddingValues {
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val bottom = if (keyboardOpen) 0.dp else LocalPageBottomSpace.current
    return PaddingValues(start = horizontal, top = vertical, end = horizontal, bottom = vertical + bottom)
}
