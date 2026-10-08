package dev.valnook.feature.investments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.repository.InstrumentRepository
import kotlinx.coroutines.flow.*

/** Catalogue selection never writes account positions or transactions. */
class PositionCreateViewModel(instruments: InstrumentRepository, private val saved: SavedStateHandle) : ViewModel() {
    private val mutableQuery = MutableStateFlow(saved.get<String>("query").orEmpty())
    val query = mutableQuery.asStateFlow()
    val available = combine(instruments.observeInstruments(), query) { catalog, query ->
        val term = query.trim()
        catalog.filter { term.isEmpty() || it.name.contains(term, true) || it.symbol.contains(term, true) ||
            it.currency.code.contains(term, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun search(value: String) { saved["query"] = value; mutableQuery.value = value }
}
