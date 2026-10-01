package dev.valnook.feature.investments

import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.valnook.designsystem.*
import dev.valnook.domain.model.AssetType
import dev.valnook.domain.repository.InvestmentRepository
import kotlinx.coroutines.flow.*

sealed interface AssetTypesState {
    data object Loading : AssetTypesState
    data object Failed : AssetTypesState
    data class Ready(val rows: List<AssetType>) : AssetTypesState
}

class AssetTypesViewModel(repository: InvestmentRepository) : ViewModel() {
    val types = repository.observe_types().map<List<AssetType>, AssetTypesState> { AssetTypesState.Ready(it) }
        .catch { emit(AssetTypesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AssetTypesState.Loading)
}
@Composable fun TypesScreen(vm: AssetTypesViewModel, onCreate: () -> Unit, onEdit: (AssetType) -> Unit) {
    val state by vm.types.collectAsStateWithLifecycle()
    val current = state as? AssetTypesState.Ready
    if (current == null) {
        Text(stringResource(if (state == AssetTypesState.Failed) R.string.instrument_types_failed_short else R.string.instrument_types_loading_short))
        return
    }
    val types = current.rows
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ActionButton(onCreate, Modifier.fillMaxWidth()) { Text(stringResource(R.string.instrument_add_type)) } }
        items(types, key = { it.id }) { type ->
            ActionButton({ onEdit(type) }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.instrument_edit_type, type.name)) }
        }
    }
}
