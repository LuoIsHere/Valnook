package dev.valnook.app.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation3.runtime.NavKey

/** Keep the toolbar in step with each entry's independently restored scroll position. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun rememberToolbarScrollState(backStack: List<NavKey>): TopAppBarState {
    val states = rememberSaveable(saver = mapSaver(
        save = { values: MutableMap<String, TopAppBarState> ->
            values.mapValues { (_, state) ->
                listOf(state.heightOffsetLimit, state.heightOffset, state.contentOffset)
            }
        },
        restore = { values ->
            values.mapValues { (_, saved) ->
                val offsets = saved as List<*>
                TopAppBarState(offsets[0] as Float, offsets[1] as Float, offsets[2] as Float)
            }.toMutableMap()
        }
    )) { mutableMapOf<String, TopAppBarState>() }
    val keys = backStack.map { it.toString() }
    SideEffect { states.keys.retainAll(keys.toSet()) }
    return states.getOrPut(keys.last()) { TopAppBarState(-Float.MAX_VALUE, 0f, 0f) }
}
