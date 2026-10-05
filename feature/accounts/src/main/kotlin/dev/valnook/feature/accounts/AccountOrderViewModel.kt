package dev.valnook.feature.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.SavingsAccount
import dev.valnook.domain.repository.AccountOrderWriter
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AccountOrderState(val accounts: List<SavingsAccount> = emptyList(), val loaded: Boolean = false,
    val busy: Boolean = false, val error: Boolean = false, val saved: Boolean = false)

class AccountOrderViewModel(private val repository: OverviewRepository, private val writer: AccountOrderWriter,
    private val savedState: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(AccountOrderState())
    val state = mutable.asStateFlow()
    private var expected = savedState.get<ArrayList<Long>>("expectedOrder")?.toList()
    init { reload() }

    fun reload() {
        if (state.value.busy) return
        viewModelScope.launch {
            try {
                val accounts = repository.snapshot().accounts
                val draft = savedState.get<ArrayList<Long>>("order")
                val byId = accounts.associateBy { it.id }
                val restored = draft?.takeIf { it.size == accounts.size && it.toSet() == byId.keys }
                if (restored == null || expected == null) expected = accounts.map { it.id }
                mutable.value = AccountOrderState(restored?.map { byId.getValue(it) } ?: accounts, loaded = true)
                persist()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(error = true) }
        }
    }

    private fun persist() {
        savedState["expectedOrder"] = ArrayList(expected.orEmpty())
        savedState["order"] = ArrayList(state.value.accounts.map { it.id })
    }

    fun reorder(keys: List<String>) {
        if (!state.value.loaded || state.value.busy || state.value.saved) return
        val accounts = state.value.accounts.associateBy { it.id.toString() }
        if (keys.size != accounts.size || keys.toSet() != accounts.keys) return
        mutable.value = state.value.copy(accounts = keys.map { accounts.getValue(it) })
        persist()
    }

    fun discardAndReload() {
        if (state.value.busy) return
        savedState.remove<ArrayList<Long>>("order")
        expected = null
        reload()
    }

    fun save() {
        if (!state.value.loaded || state.value.busy || state.value.saved) return
        mutable.value = state.value.copy(busy = true, error = false)
        viewModelScope.launch {
            try {
                writer.saveOrder(requireNotNull(expected), state.value.accounts.map { it.id })
                mutable.value = state.value.copy(busy = false, saved = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = state.value.copy(busy = false, error = true) }
        }
    }
}
