package dev.valnook.feature.settings

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.NavigationItemId

private val GROUP_CORNER = 18.dp

/**
 * The bottom navigation, edited in place: a preview of the bar, the pages on it (drag to reorder, ⊖ to hide) and
 * the hidden ones (⊕ to bring back). The toolbar's save writes the draft.
 */
@Composable
fun NavigationSettingsScreen(vm: NavigationSettingsViewModel,
    onToolbarChange: (NavigationToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) {
        CircularProgressIndicator()
        return
    }
    val listState = rememberLazyListState()
    var localOrder by remember { mutableStateOf(state.shown) }
    var dragged by remember { mutableStateOf<NavigationItemId?>(null) }
    var dragStart by remember { mutableFloatStateOf(0f) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val latestShown by rememberUpdatedState(state.shown)
    val toolbar = remember(vm, state.busy, state.dirty, dragged) {
        NavigationToolbarState(state.busy, dragged != null, state.dirty, vm::save)
    }
    SideEffect { onToolbarChange(toolbar) }
    DisposableEffect(onToolbarChange) { onDispose { onToolbarChange(null) } }
    // While a row is held the list follows the finger; otherwise it is the draft as it is.
    val shown = if (dragged != null) localOrder else state.shown
    val hidden = state.hidden
    val editable = !state.busy

    fun finishDrag(commit: Boolean) {
        if (commit && dragged != null && localOrder != latestShown) vm.reorderShown(localOrder)
        dragged = null
        dragStart = 0f
        dragDistance = 0f
    }

    val reorder = stringResource(R.string.settings_reorder)
    val moveUp = stringResource(R.string.settings_move_up)
    val moveDown = stringResource(R.string.settings_move_down)
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = pageContentPadding()) {
        item(key = "preview") { NavigationPreview(shown) }
        item(key = "header-shown") {
            GroupLabel(stringResource(R.string.settings_navigation_shown, shown.size), Modifier.padding(top = Space.lg))
        }
        itemsIndexed(shown, key = { _, item -> item.name }) { index, item ->
            val dragging = dragged == item
            val label = navigationLabel(item)
            // Plain holders: they change on every frame of a drag and must not recompose the row.
            val handle = remember { arrayOfNulls<LayoutCoordinates>(1) }
            var downY by remember { mutableFloatStateOf(0f) }
            GroupRow(index, shown.size, dragging, Modifier
                .animateItem(fadeInSpec = null, placementSpec = if (dragging) null else tween(160), fadeOutSpec = null)
                .graphicsLayer {
                    val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.name }?.offset?.toFloat()
                        ?: dragStart
                    translationY = if (dragging) dragStart + dragDistance - offset else 0f
                }
                .zIndex(if (dragging) 1f else 0f)
                .testTag("navigation-row-${item.name.lowercase()}")) {
                SignButton(plus = false, enabled = editable && item != NavigationItemId.SETTINGS,
                    description = stringResource(R.string.settings_navigation_hide_page, label),
                    modifier = Modifier.testTag("navigation-visible-${item.name.lowercase()}")) { vm.hide(item) }
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    if (item == NavigationItemId.SETTINGS) Text(stringResource(R.string.settings_navigation_always),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FaIcon(FaIcons.gripLines, null, Modifier.size(48.dp)
                    .testTag("navigation-reorder-${item.name.lowercase()}")
                    .semantics {
                        contentDescription = reorder
                        if (!editable) disabled()
                        customActions = listOf(
                            CustomAccessibilityAction(moveUp) {
                                val ok = editable && index > 0
                                if (ok) vm.reorderShown(shown.toMutableList().apply { add(index - 1, removeAt(index)) })
                                ok
                            },
                            CustomAccessibilityAction(moveDown) {
                                val ok = editable && index < shown.lastIndex
                                if (ok) vm.reorderShown(shown.toMutableList().apply { add(index + 1, removeAt(index)) })
                                ok
                            })
                    }
                    // The finger is measured on screen from where it went down: the row moves with it, and the
                    // drag only starts after a few pixels, so distances inside the handle would fall behind.
                    .onGloballyPositioned { handle[0] = it }
                    .pointerInput(item, editable) {
                        if (!editable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            downY = handle[0]?.localToWindow(down.position)?.y ?: 0f
                        }
                    }
                    .pointerInput(item, editable) {
                        if (!editable) return@pointerInput
                        detectVerticalDragGestures(
                            onDragStart = {
                                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == item.name }
                                if (info != null) {
                                    localOrder = latestShown
                                    dragged = item
                                    dragStart = info.offset.toFloat()
                                    dragDistance = 0f
                                }
                            },
                            onDragEnd = { finishDrag(commit = true) },
                            onDragCancel = { finishDrag(commit = false) },
                            onVerticalDrag = { change, distance ->
                                change.consume()
                                if (dragged != item) return@detectVerticalDragGestures
                                val fingerY = handle[0]?.localToWindow(change.position)?.y
                                dragDistance = if (fingerY != null) fingerY - downY else dragDistance + distance
                                val visible = listState.layoutInfo.visibleItemsInfo
                                val info = visible.firstOrNull { it.key == item.name } ?: return@detectVerticalDragGestures
                                val from = localOrder.indexOf(item)
                                // Swap with a neighbour once the held row's centre passes the neighbour's centre.
                                val center = dragStart + dragDistance + info.size / 2f
                                val previous = localOrder.getOrNull(from - 1)?.let { p -> visible.firstOrNull { it.key == p.name } }
                                val next = localOrder.getOrNull(from + 1)?.let { n -> visible.firstOrNull { it.key == n.name } }
                                val target = when {
                                    previous != null && center < previous.offset + previous.size / 2f -> from - 1
                                    next != null && center > next.offset + next.size / 2f -> from + 1
                                    else -> from
                                }
                                if (target != from) localOrder = localOrder.toMutableList().apply { add(target, removeAt(from)) }
                            })
                    }
                    .padding(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (editable) 1f else .38f))
            }
        }
        item(key = "header-hidden") {
            GroupLabel(stringResource(R.string.settings_navigation_hidden_count, hidden.size), Modifier.padding(top = Space.lg))
        }
        if (hidden.isEmpty()) item(key = "hidden-empty") {
            GroupRow(0, 1, false, Modifier.animateItem()) {
                Text(stringResource(R.string.settings_navigation_none_hidden), Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else itemsIndexed(hidden, key = { _, item -> item.name }) { index, item ->
            val label = navigationLabel(item)
            GroupRow(index, hidden.size, false, Modifier
                .animateItem(fadeInSpec = null, placementSpec = tween(160), fadeOutSpec = null)
                .testTag("navigation-row-${item.name.lowercase()}")) {
                SignButton(plus = true, enabled = editable,
                    description = stringResource(R.string.settings_navigation_show_page, label),
                    modifier = Modifier.testTag("navigation-visible-${item.name.lowercase()}")) { vm.show(item) }
                Text(label, Modifier.weight(1f).padding(vertical = 14.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        item(key = "hint") {
            Text(stringResource(R.string.settings_navigation_hint), Modifier.padding(start = 4.dp, end = 4.dp, top = Space.md),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.error?.let { error -> item(key = "error") {
            Column(Modifier.padding(top = Space.md)) {
                Text(stringResource(R.string.settings_error_conflict), color = MaterialTheme.colorScheme.error)
                if (error == ErrorCode.STALE_RECORD) TextButton(vm::discardAndReload) {
                    Text(stringResource(R.string.settings_reload_draft))
                }
            }
        } }
        if (state.savedNotice) item(key = "saved") {
            Text(stringResource(R.string.settings_saved), Modifier.padding(start = 4.dp, top = Space.md))
        }
    }
}

/** The bar as it will look, on a framed strip. It only shows; touches never reach it. */
@Composable
private fun NavigationPreview(items: List<NavigationItemId>) {
    val names = items.map { navigationLabel(it) }.joinToString(stringResource(R.string.settings_navigation_separator))
    val description = stringResource(R.string.settings_navigation_preview, names)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GroupLabel(stringResource(R.string.settings_navigation_preview_label))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(GROUP_CORNER))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .testTag("navigation-preview")
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 12.dp, vertical = 20.dp)) {
            NavigationCapsule(items, items.first(), {}, ::navigationIcon, { navigationLabel(it) })
            Box(Modifier.matchParentSize().pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            })
        }
    }
}

@Composable
private fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(start = 4.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One line of a grouped card. Each line paints its own part of the card, so lines can move on their own. */
@Composable
private fun GroupRow(index: Int, count: Int, raised: Boolean, modifier: Modifier,
    content: @Composable RowScope.() -> Unit) {
    val top = if (index == 0 || raised) GROUP_CORNER else 0.dp
    val bottom = if (index == count - 1 || raised) GROUP_CORNER else 0.dp
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    Surface(modifier.fillMaxWidth(), shape = shape,
        color = if (raised) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (raised) 6.dp else 0.dp) {
        Column {
            if (index > 0 && !raised) HorizontalDivider(Modifier.padding(start = 56.dp),
                color = MaterialTheme.colorScheme.surfaceContainer)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically, content = content)
        }
    }
}

/** A filled circle with − or +, in the same red and green as the status tags. */
@Composable
internal fun SignButton(plus: Boolean, enabled: Boolean, description: String, modifier: Modifier, onClick: () -> Unit) {
    IconButton(onClick, modifier.semantics { contentDescription = description }, enabled = enabled) { SignIcon(plus, enabled) }
}

/** The round ⊕ / ⊖ mark alone, for a row that is itself the button. */
@Composable
internal fun SignIcon(plus: Boolean, enabled: Boolean) {
    val palette = LocalTagPalette.current
    val base = if (plus) palette.positive.content else palette.negative.content
    val color = if (enabled) base else base.copy(alpha = .3f)
    // The dark theme's tag colours are light, so the sign on them is drawn dark.
    val sign = if (base.luminance() > .5f) Color(0xFF202023) else Color.White
    Canvas(Modifier.size(22.dp)) {
        drawCircle(color)
        val arm = size.minDimension * .26f
        val width = 2.dp.toPx()
        drawLine(sign, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), width, StrokeCap.Round)
        if (plus) drawLine(sign, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), width, StrokeCap.Round)
    }
}
