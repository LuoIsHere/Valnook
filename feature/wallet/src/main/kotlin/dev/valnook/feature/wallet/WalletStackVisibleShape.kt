package dev.valnook.feature.wallet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Keep only the exposed lip; a zero gap fully hides a card behind its neighbour. */
internal class WalletStackVisibleShape(private val hasNext:Boolean,private val gapFraction:Float=1f):Shape {
    override fun createOutline(size:Size,layoutDirection:LayoutDirection,density:Density):Outline {
        val radius=with(density){18.dp.toPx()}
        val face=Path().apply{addRoundRect(RoundRect(0f,0f,size.width,size.height,CornerRadius(radius)))}
        if(!hasNext)return Outline.Generic(face)
        val peek=with(density){60.dp.toPx()}*gapFraction.coerceAtLeast(0f)
        val front=Path().apply{addRoundRect(RoundRect(0f,peek,size.width,peek+size.height,CornerRadius(radius)))}
        return Outline.Generic(Path.combine(PathOperation.Difference,face,front))
    }
}
