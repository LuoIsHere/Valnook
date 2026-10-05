package dev.valnook.designsystem

import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs

data class ReorderItem(val key: String, val title: String, val subtitle: String = "")

@Composable
fun SortIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        for (line in 0..2) {
            val y = size.height * (0.25f + line * 0.25f)
            drawLine(color, Offset(size.width * 0.15f, y),
                Offset(size.width * (0.85f - line * 0.2f), y), 2.dp.toPx(), StrokeCap.Round)
        }
    }
}

/** Stable keys and a fixed screen-space drag position prevent animated neighbours from pulling the row. */
@Composable
fun ReorderList(items: List<ReorderItem>, onReorder: (List<String>) -> Unit,
    reorderLabel: String, moveUpLabel: String, moveDownLabel: String,
    modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp)) {
    val listState = rememberLazyListState()
    val latestItems by rememberUpdatedState(items)
    val latestReorder by rememberUpdatedState(onReorder)
    var order by remember { mutableStateOf(items.map { it.key }) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var pressedKey by remember { mutableStateOf<String?>(null) }
    var startOffset by remember { mutableFloatStateOf(0f) }
    var distance by remember { mutableFloatStateOf(0f) }
    val byKey = items.associateBy { it.key }

    LaunchedEffect(items.map { it.key }, dragged) {
        if (dragged == null) order = items.map { it.key }
    }
    fun reorderAtPointer() {
        val key = dragged ?: return
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val from = order.indexOf(key)
        if (from < 0) return
        val center = startOffset + distance + info.size / 2f
        val previous = order.getOrNull(from - 1)?.let { id -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } }
        val next = order.getOrNull(from + 1)?.let { id -> listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } }
        val target = when {
            previous != null && center < previous.offset + previous.size / 2f -> from - 1
            next != null && center > next.offset + next.size / 2f -> from + 1
            else -> from
        }
        if (target != from) order = order.toMutableList().apply { add(target, removeAt(from)) }
    }
    fun finish(commit: Boolean) {
        if (commit && dragged != null) latestReorder(order)
        else order = latestItems.map { it.key }
        dragged = null
        distance = 0f
    }
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        while (dragged != null) {
            withFrameNanos { }
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == dragged } ?: continue
            val layout = listState.layoutInfo
            val center = startOffset + distance + info.size / 2f
            val margin = info.size.coerceAtMost(100).toFloat()
            val delta = when {
                center < layout.viewportStartOffset + margin -> -12f
                center > layout.viewportEndOffset - margin -> 12f
                else -> 0f
            }
            if (abs(delta) > 0 && listState.scrollBy(delta) != 0f) reorderAtPointer()
        }
    }
    Box(modifier) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = contentPadding) {
            items(order, key = { it }) { key ->
                val item = byKey[key] ?: return@items
                val dragging = dragged == key
                val index = order.indexOf(key)
                Column(Modifier.fillMaxWidth()
                    .animateItem(fadeInSpec = null, placementSpec = if (dragging) null else tween(140), fadeOutSpec = null)
                    .graphicsLayer {
                        val offset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.offset ?: 0
                        translationY = if (dragging) startOffset + distance - offset else 0f
                    }.zIndex(if (dragging) 1f else 0f)) {
                    Surface(color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().testTag("sort-row-$key").padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(item.title, style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (item.subtitle.isNotBlank()) Text(item.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            val handleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f)
                            Canvas(Modifier.size(48.dp).testTag("sort-handle-$key").semantics {
                                contentDescription = reorderLabel
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUpLabel) {
                                        if (enabled && index > 0) latestReorder(order.toMutableList().apply { add(index - 1, removeAt(index)) })
                                        enabled && index > 0
                                    },
                                    CustomAccessibilityAction(moveDownLabel) {
                                        if (enabled && index < order.lastIndex) latestReorder(order.toMutableList().apply { add(index + 1, removeAt(index)) })
                                        enabled && index < order.lastIndex
                                    })
                                if (!enabled) disabled()
                            }) {
                                for (line in 0..2) {
                                    val y = size.height / 2f + (line - 1) * 5.dp.toPx()
                                    drawLine(handleColor, Offset(size.width / 2f - 9.dp.toPx(), y),
                                        Offset(size.width / 2f + 9.dp.toPx(), y), 2.dp.toPx(), StrokeCap.Round)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
        // Keep the gesture owner outside lazy items: scrolling can dispose a moving row's pointer node.
        Box(Modifier.align(Alignment.CenterEnd).width(48.dp).fillMaxHeight().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressedKey = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    down.position.y >= it.offset && down.position.y < it.offset + it.size
                }?.key as? String
            }
        }.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectVerticalDragGestures(onDragStart = {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == pressedKey }?.let { info ->
                    dragged = pressedKey
                    startOffset = info.offset.toFloat()
                    distance = 0f
                }
            }, onDragEnd = { finish(true) }, onDragCancel = { finish(false) },
                onVerticalDrag = { change, amount ->
                    change.consume()
                    if (dragged != null) { distance += amount; reorderAtPointer() }
                })
        })
    }
}
