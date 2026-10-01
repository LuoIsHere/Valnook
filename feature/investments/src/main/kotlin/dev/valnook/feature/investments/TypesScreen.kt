package dev.valnook.feature.investments

import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        Text(if (state == AssetTypesState.Failed) "类型读取失败，请返回后重试" else "正在读取类型")
        return
    }
    val types = current.rows
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ActionButton(onCreate, Modifier.fillMaxWidth()) { Text("新增类型") } }
        items(types, key = { it.id }) { type ->
            ActionButton({ onEdit(type) }, Modifier.fillMaxWidth()) { Text(type.name + " · 编辑") }
        }
    }
}
