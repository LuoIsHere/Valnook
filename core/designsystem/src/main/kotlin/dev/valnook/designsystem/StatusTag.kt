package dev.valnook.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class TagTone { NEGATIVE, POSITIVE, ATTENTION, NEUTRAL }

data class TagColors(val container: Color, val content: Color)
data class TagPalette(val negative: TagColors, val positive: TagColors, val attention: TagColors, val neutral: TagColors)

internal val LightTags = TagPalette(
    TagColors(Color(0xFFFBEDE8), Color(0xFFA33A22)), TagColors(Color(0xFFE4F3F0), Color(0xFF0B6E61)),
    TagColors(Color(0xFFFBF1DC), Color(0xFF855808)), TagColors(Color(0xFFE8E8EC), Color(0xFF56565D)))
internal val DarkTags = TagPalette(
    TagColors(Color(0xFF4A211A), Color(0xFFFFB4A3)), TagColors(Color(0xFF12392F), Color(0xFF7FDCC3)),
    TagColors(Color(0xFF43320C), Color(0xFFF2C76B)), TagColors(Color(0xFF303035), Color(0xFFC4C4CC)))

val LocalTagPalette = staticCompositionLocalOf { LightTags }

/** A short state label. The words carry the meaning; the color only helps it stand out. */
@Composable fun StatusTag(text: String, tone: TagTone, modifier: Modifier = Modifier) {
    val palette = LocalTagPalette.current
    val colors = when (tone) {
        TagTone.NEGATIVE -> palette.negative
        TagTone.POSITIVE -> palette.positive
        TagTone.ATTENTION -> palette.attention
        TagTone.NEUTRAL -> palette.neutral
    }
    Text(text, modifier.background(colors.container, RoundedCornerShape(percent = 50)).padding(horizontal = 8.dp, vertical = 1.dp),
        color = colors.content, style = MaterialTheme.typography.labelSmall, maxLines = 1)
}
