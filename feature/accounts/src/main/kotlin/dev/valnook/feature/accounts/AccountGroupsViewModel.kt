package dev.valnook.feature.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.AccountGroup
import dev.valnook.domain.model.AssetSnapshot
import dev.valnook.domain.model.SavingsAccount
import dev.valnook.domain.repository.AccountGroupWriter
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

typealias GroupedAccounts = AccountGroupSection<SavingsAccount>

data class AccountGroupsState(val sections: List<GroupedAccounts> = emptyList(), val loaded: Boolean = false,
    val loadFailed: Boolean = false, val busy: Boolean = false, val saveFailed: Boolean = false)

/**
 * Account group management without an edit mode: every change is written at once. The list follows the
 * database; after a reorder the new order is shown locally until the database reports it.
 */
class AccountGroupsViewModel(overview: OverviewRepository, private val writer: AccountGroupWriter) : ViewModel() {
    private val refresh = MutableStateFlow(0)
    private val preview = MutableStateFlow<List<GroupedAccounts>?>(null)
    private val busy = MutableStateFlow(false)
    private val saveFailed = MutableStateFlow(false)
    private var stored: List<GroupedAccounts>? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val storedSections = refresh.flatMapLatest {
        overview.observeSnapshot().map<AssetSnapshot, List<GroupedAccounts>?> { snapshot ->
            sectionsOf(snapshot.groups, snapshot.accounts) { it.groupId }
        }.catch { emit(null) }
    }.onEach { sections ->
        stored = sections
        if (!busy.value) preview.value = null
    }

    val state = combine(storedSections, preview, busy, saveFailed) { sections, local, writing, failed ->
        if (sections == null) AccountGroupsState(loadFailed = true)
        else AccountGroupsState(local ?: sections, loaded = true, busy = writing, saveFailed = failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AccountGroupsState())

    fun reload() { refresh.update { it + 1 } }

    fun create(name: String) = write { writer.create(name) }
    fun rename(group: AccountGroup, name: String) = write { writer.rename(group.id, name) }
    fun delete(group: AccountGroup) = write { writer.delete(group.id) }
    fun addAccounts(group: AccountGroup, accountIds: List<Long>) = write { writer.moveAccounts(group.id, accountIds) }

    fun reorderGroups(groupIds: List<Long>) {
        val byId = state.value.sections.associateBy { it.group.id }
        if (groupIds.toSet() != byId.keys) return
        write(groupIds.map(byId::getValue)) { writer.reorderGroups(groupIds) }
    }

    /** [keys] comes from the grouped reorder list; a drag only ever changes one group. */
    fun reorderAccounts(keys: List<String>) {
        val sections = state.value.sections
        val accounts = sections.flatMap { it.items }.associateBy { accountKey(it.id) }
        val reordered = sections.map { section ->
            val start = keys.indexOf(groupKey(section.group.id))
            if (start < 0) return@map section
            val ids = keys.drop(start + 1).takeWhile { it in accounts }
            section.copy(items = ids.map(accounts::getValue))
        }
        val changed = reordered.zip(sections).singleOrNull { (new, old) -> new.items != old.items }?.first ?: return
        if (changed.items.toSet() != sections.first { it.group == changed.group }.items.toSet()) return
        write(reordered) { writer.reorderAccounts(changed.group.id, changed.items.map { it.id }) }
    }

    private fun write(local: List<GroupedAccounts>? = null, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        saveFailed.value = false
        local?.let { preview.value = it }
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed.value = true
                preview.value = null
            } finally {
                busy.value = false
                // Otherwise the next database emission clears it.
                if (preview.value == stored) preview.value = null
            }
        }
    }

    companion object {
        fun groupKey(id: Long) = "group-$id"
        fun accountKey(id: Long) = "account-$id"
    }
}
