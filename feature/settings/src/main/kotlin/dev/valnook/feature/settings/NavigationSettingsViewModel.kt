package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

data class NavigationSettingsState(
    val saved: NavigationConfiguration = NavigationConfiguration(),
    val draft: NavigationConfiguration = NavigationConfiguration(),
    val baselineRevision: Long = 0,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val savedNotice: Boolean = false,
    val error: ErrorCode? = null
) {
    val dirty: Boolean get() = draft != saved
    val shown: List<NavigationItemId> get() = draft.visibleInOrder
    val hidden: List<NavigationItemId> get() = draft.order.filter { it !in draft.visible }
}

/** The page is a draft from the moment it opens; nothing is written until [save]. */
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
            // A failed read leaves the page as it is rather than ending the app.
            repository.observeSettings().catch { }.collect { settings ->
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
                        handle.get<Long>(KEY_BASELINE) ?: settings.revision, loaded = true)
                } else {
                    // Unsaved changes stay; without any, the page follows a change made elsewhere.
                    mutable.value = if (current.dirty) current.copy(saved = settings.navigation) else
                        current.copy(saved = settings.navigation, draft = settings.navigation,
                            baselineRevision = settings.revision)
                }
            }
        }
    }

    fun discardAndReload() = update(state.value.copy(draft = state.value.saved,
        baselineRevision = latestRevision, error = null, savedNotice = false))

    /** Takes [item] off the bar; it goes to the end of the hidden pages. Settings always stays. */
    fun hide(item: NavigationItemId) {
        val current = state.value
        if (!editable(current) || item == NavigationItemId.SETTINGS || item !in current.draft.visible) return
        change(current, current.shown - item, current.hidden + item)
    }

    /** Puts [item] back on the bar, after the pages already there, while the bar has room. */
    fun show(item: NavigationItemId) {
        val current = state.value
        if (!editable(current) || item in current.draft.visible || !current.draft.canShowMore) return
        change(current, current.shown + item, current.hidden - item)
    }

    /** A new order for the pages on the bar; hidden pages keep theirs. */
    fun reorderShown(order: List<NavigationItemId>) {
        val current = state.value
        if (!editable(current) || order.size != current.shown.size || order.toSet() != current.shown.toSet()) return
        change(current, order, current.hidden)
    }

    fun save() {
        val current = state.value
        if (!editable(current) || !current.dirty) return
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

    private fun editable(current: NavigationSettingsState) = current.loaded && !current.busy

    private fun change(current: NavigationSettingsState, shown: List<NavigationItemId>, hidden: List<NavigationItemId>) =
        update(current.copy(draft = NavigationConfiguration(shown + hidden, shown.toSet()), savedNotice = false))

    private fun update(value: NavigationSettingsState) {
        mutable.value = value
        handle[KEY_ORDER] = ArrayList(value.draft.order.map { it.name })
        handle[KEY_VISIBLE] = ArrayList(value.draft.visibleInOrder.map { it.name })
        handle[KEY_BASELINE] = value.baselineRevision
    }

    private companion object {
        const val KEY_ORDER = "navigation-order"
        const val KEY_VISIBLE = "navigation-visible"
        const val KEY_BASELINE = "navigation-baseline-revision"
    }
}
