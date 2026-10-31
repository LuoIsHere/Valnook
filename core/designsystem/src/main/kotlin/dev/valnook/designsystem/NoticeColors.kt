package dev.valnook.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Soft pink for non-blocking notices that something in the background did not work (such as an exchange rate
 * update). Text on the container stays above 8:1 contrast in both themes.
 */
data class NoticePalette(val container: Color, val onContainer: Color)

internal val LightNotice = NoticePalette(Color(0xFFF8D7E3), Color(0xFF6B1A3B))
internal val DarkNotice = NoticePalette(Color(0xFF5A2038), Color(0xFFFFD9E4))

val LocalNoticePalette = staticCompositionLocalOf { LightNotice }
