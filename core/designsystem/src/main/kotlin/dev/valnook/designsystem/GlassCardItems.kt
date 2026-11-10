package dev.valnook.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The corner of a [GlassCard]. */
private val CARD_RADIUS = 18.dp

/** A piece of a card: rounded only where the card begins or ends, square where it goes on into the next piece. */
fun glassCardPieceShape(first: Boolean, last: Boolean): Shape = RoundedCornerShape(
    topStart = if (first) CARD_RADIUS else 0.dp, topEnd = if (first) CARD_RADIUS else 0.dp,
    bottomEnd = if (last) CARD_RADIUS else 0.dp, bottomStart = if (last) CARD_RADIUS else 0.dp)

/**
 * The rows of one [GlassCard] as lazy items of their own, so a long card composes only the rows on screen. The pieces
 * meet with no seam and look like the one card.
 *
 * A lazy list's arrangement would also open between the pieces, so a list using this spaces its items with padding
 * instead: [gap] goes above the card, the list's other items put the same padding above themselves.
 *
 * [name] keeps the keys apart from the list's other items and other cards; [cardModifier] goes on the first piece,
 * which stands for the card; [divider] separates rows; [footer], when given, is the card's last piece after the rows,
 * drawn as it is (with its own divider, if it wants one).
 */
fun <T> LazyListScope.glassCardItems(items: List<T>, key: (T) -> Any, name: String, gap: Dp = 0.dp,
    cardModifier: Modifier = Modifier, divider: @Composable () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null, row: @Composable (T) -> Unit) {
    val count = items.size + if (footer != null) 1 else 0
    itemsIndexed(items, key = { _, item -> "$name:${key(item)}" }, contentType = { _, _ -> "$name:row" }) { index, item ->
        GlassCardPiece(index == 0, index == count - 1, gap, modifier = cardModifier) {
            if (index > 0) divider()
            row(item)
        }
    }
    if (footer != null) item(key = "$name:footer", contentType = "$name:footer") {
        GlassCardPiece(items.isEmpty(), true, gap, modifier = cardModifier, content = footer)
    }
}

// [modifier] is the caller's card modifier; it applies to the first piece only.
@Composable private fun GlassCardPiece(first: Boolean, last: Boolean, gap: Dp, modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit) {
    GlassSurface((if (first) Modifier.padding(top = gap).then(modifier) else Modifier).fillMaxWidth(),
        glassCardPieceShape(first, last)) { Column(content = content) }
}
