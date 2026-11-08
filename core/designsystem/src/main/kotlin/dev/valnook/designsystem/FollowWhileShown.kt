package dev.valnook.designsystem

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val NOT_HANDLED = Any()

/**
 * Follows [revisions] only while [shown] (a page's state) has collectors, calling [onChange] for each revision not
 * handled yet. A page kept in the back stack, under another tab or a page opened over it, then does no work while it
 * is hidden, and catches up once when shown again if the data changed meanwhile.
 */
suspend fun <T> followWhileShown(shown: MutableStateFlow<*>, revisions: Flow<T>, onChange: () -> Unit) {
    var handled: Any? = NOT_HANDLED
    shown.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
        if (visible) revisions.distinctUntilChanged().collect { revision ->
            if (revision != handled) {
                handled = revision
                onChange()
            }
        }
    }
}
