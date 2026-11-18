package dev.valnook.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Whether the page around it has come to rest: false while a [SwipeTabs] pager is still moving to or past it.
 * Charts wait for it before drawing themselves in, so a page only passed on the way to another one does not use up
 * its entrance. Outside a pager it is always true.
 */
val LocalPageSettled = staticCompositionLocalOf<() -> Boolean> { { true } }

/**
 * [UnderlineTabRow] over pages that also follow the finger sideways. The underline moves with the pages as they
 * slide; a tap on a tab slides straight to its page. [onSelected] hears the page the pager comes to rest on.
 *
 * Each page keeps its own state, scroll position included, while another one shows; the pages next to the one
 * showing stay composed off screen. A drag a page consumes for
 * itself (a chart being scrubbed) does not turn the page. [key] names each page for that state and must be saveable.
 */
@Composable
fun <T> SwipeTabs(
    options: List<T>,
    selected: T,
    onSelected: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    tabRowModifier: Modifier = Modifier,
    optionModifier: (T) -> Modifier = { Modifier },
    key: (T) -> Any = { it.toString() },
    above: @Composable () -> Unit = {},
    page: @Composable (T) -> Unit,
) {
    val pager = rememberPagerState(options.indexOf(selected).coerceAtLeast(0)) { options.size }
    val scope = rememberCoroutineScope()
    val currentOnSelected by rememberUpdatedState(onSelected)
    LaunchedEffect(pager, options) {
        snapshotFlow { pager.settledPage }.collect { index -> options.getOrNull(index)?.let { currentOnSelected(it) } }
    }
    // Chosen elsewhere (restored, or set by the page's owner): go there without sliding through the pages between.
    LaunchedEffect(selected) {
        val target = options.indexOf(selected)
        if (target >= 0 && target != pager.settledPage && !pager.isScrollInProgress) pager.scrollToPage(target)
    }
    Column(modifier) {
        UnderlineTabRow(options, options.getOrElse(pager.currentPage) { selected }, { option ->
            val target = options.indexOf(option)
            // One slide all the way: pages passed on the way never count as at rest, so they keep their entrance.
            if (target >= 0 && target != pager.currentPage) scope.launch {
                pager.animateScrollToPage(target, animationSpec = tween(260 + 80 * abs(target - pager.currentPage),
                    easing = FastOutSlowInEasing))
            }
        }, label, tabRowModifier, optionModifier, progress = { pager.currentPage + pager.currentPageOffsetFraction })
        above()
        // The pages either side are ready before the finger brings them in, data and all; their entrances still wait.
        HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), beyondViewportPageCount = 1,
            key = { key(options[it]) }) { index ->
            val settled = remember(pager, index) { { !pager.isScrollInProgress && pager.settledPage == index } }
            CompositionLocalProvider(LocalPageSettled provides settled) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) { page(options[index]) }
            }
        }
    }
}
