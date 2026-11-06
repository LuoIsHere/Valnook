package dev.valnook.app.navigation

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/**
 * Whether the bottom navigation is folded to one tab. A root page that scrolls its content down past [thresholdPx]
 * folds it; only reaching the top again or tapping the folded capsule opens it. Scrolling back up part way does not.
 * While [paused] (a card's details, the keyboard, a sub-page) scrolling leaves it as it is, so it comes back unchanged.
 */
@Stable
internal class NavigationCollapse(state: MutableState<Boolean>, private val thresholdPx: Float) {
    var folded by state
        private set
    var paused = false
    /** How far the content has moved down since the bar was last opened. */
    private var travelled = 0f

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (paused || folded) return Offset.Zero
            // A negative y means the content moved up: the reader is going down the page.
            if (consumed.y < 0f) {
                travelled -= consumed.y
                if (travelled > thresholdPx) folded = true
            } else if (consumed.y > 0f) travelled = 0f // Small back-and-forth moves never add up to a fold.
            return Offset.Zero
        }
    }

    fun expand() {
        folded = false
        travelled = 0f
    }

    /** At the top of the page there is nothing to fold for. */
    fun atTop() {
        if (!paused) expand()
    }
}
