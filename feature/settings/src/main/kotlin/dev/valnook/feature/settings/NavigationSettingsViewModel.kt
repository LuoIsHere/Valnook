package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NavigationSettingsState(
    val saved: NavigationConfiguration = NavigationConfiguration(),
    val draft: NavigationConfiguration = NavigationConfiguration(),
    val baselineRevision: Long = 0,
    val loaded: Boolean = false,
    val editing: Boolean = false,
    val busy: Boolean = false,
    val savedNotice: Boolean = false,
    val error: ErrorCode? = null
)

class NavigationSettingsViewModel(
    repository: SettingsRepository,
    private val writer: SettingsWriter,
    private val handle: SavedStateHandle
) : ViewModel() {
    private val mutable = MutableStateFlow(NavigationSettingsState())
    val state = mutable.asStateFlow()
    private var latestRevision = 0L

    init {
        viewModelScope.launch {
            repository.observeSettings().collect { settings ->
                latestRevision = settings.revision
                val current = state.value
                if (!current.loaded) {
                    val restoredOrder = handle.get<ArrayList<String>>(KEY_ORDER)?.mapNotNull {
                        runCatching { NavigationItemId.valueOf(it) }.getOrNull()
                    }
                    val restoredVisible = handle.get<ArrayList<String>>(KEY_VISIBLE)?.mapNotNull {
                        runCatching { NavigationItemId.valueOf(it) }.getOrNull()
                    }?.toSet()
                    val restored = runCatching {
                        if (restoredOrder == null || restoredVisible == null) settings.navigation
                        else NavigationConfiguration(restoredOrder, restoredVisible)
                    }.getOrDefault(settings.navigation)
                    mutable.value = NavigationSettingsState(settings.navigation, restored,
                        handle.get<Long>(KEY_BASELINE) ?: settings.revision,
                        loaded = true, editing = handle[KEY_EDITING] ?: false)
                } else {
                    mutable.value = if (current.editing) current.copy(saved = settings.navigation) else
                        current.copy(saved = settings.navigation, draft = settings.navigation,
                            baselineRevision = settings.revision)
                }
            }
        }
    }

    fun edit() = update(state.value.copy(editing = true, savedNotice = false, error = null))
    fun cancel() = update(state.value.copy(draft = state.value.saved, editing = false, error = null))
    fun discardAndReload() = update(state.value.copy(draft = state.value.saved,
        baselineRevision = latestRevision, error = null, savedNotice = false))

    fun toggle(item: NavigationItemId) {
        val current = state.value
        if (!current.editing || item == NavigationItemId.SETTINGS) return
        val visible = current.draft.visible.toMutableSet().apply {
            if (!add(item)) remove(item)
        }
        update(current.copy(draft = NavigationConfiguration(current.draft.order, visible), savedNotice = false))
    }

    fun move(item: NavigationItemId, delta: Int) {
        val current = state.value
        if (!current.editing) return
        val order = current.draft.order.toMutableList()
        val from = order.indexOf(item)
        val to = (from + delta).coerceIn(order.indices)
        if (from == to) return
        order.removeAt(from)
        order.add(to, item)
        update(current.copy(draft = NavigationConfiguration(order, current.draft.visible), savedNotice = false))
    }

    fun save() {
        val current = state.value
        if (current.busy || !current.editing) return
        mutable.value = current.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val stored = writer.applyChange(SaveNavigationConfiguration(current.baselineRevision, current.draft))
                update(NavigationSettingsState(stored.navigation, stored.navigation, stored.revision,
                    loaded = true, savedNotice = true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DomainException) {
                mutable.value = current.copy(busy = false, error = error.code)
            } catch (_: Exception) {
                mutable.value = current.copy(busy = false, error = ErrorCode.STALE_RECORD)
            }
        }
    }

    private fun update(value: NavigationSettingsState) {
        mutable.value = value
        handle[KEY_ORDER] = ArrayList(value.draft.order.map { it.name })
        handle[KEY_VISIBLE] = ArrayList(value.draft.visibleInOrder.map { it.name })
        handle[KEY_EDITING] = value.editing
        handle[KEY_BASELINE] = value.baselineRevision
    }

    private companion object {
        const val KEY_ORDER = "navigation-order"
        const val KEY_VISIBLE = "navigation-visible"
        const val KEY_EDITING = "navigation-editing"
        const val KEY_BASELINE = "navigation-baseline-revision"
    }
}
