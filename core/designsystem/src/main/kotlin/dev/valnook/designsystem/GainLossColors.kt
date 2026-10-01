package dev.valnook.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class GainLossPalette(val gain: Color, val loss: Color, val neutral: Color)

val LocalGainLossPalette = staticCompositionLocalOf {
    GainLossPalette(Color(0xFF168457), Color(0xFFC62828), Color.Unspecified)
}
