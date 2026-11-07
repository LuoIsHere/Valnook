package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.AssetType
import dev.valnook.domain.repository.InvestmentRepository
import kotlinx.coroutines.flow.*

/** [counts]: how many instruments each class holds, by class ID. */
sealed interface AssetTypesState {
    data object Loading : AssetTypesState
    data object Failed : AssetTypesState
    data class Ready(val rows: List<AssetType>, val counts: Map<Long, Int> = emptyMap()) : AssetTypesState
}

/** [instrumentTypes]: the class of every instrument, counted per class. */
class AssetTypesViewModel(repository: InvestmentRepository, instrumentTypes: Flow<List<Long>> = flowOf(emptyList())) : ViewModel() {
    val types = combine(repository.observe_types(), instrumentTypes) { rows, ids ->
        AssetTypesState.Ready(rows, ids.groupingBy { it }.eachCount()) as AssetTypesState
    }.catch { emit(AssetTypesState.Failed) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), AssetTypesState.Loading)
}

/** Adding a class, in the title bar. */
@Composable fun TypesActions(onCreate: () -> Unit) {
    val label = stringResource(R.string.instrument_add_type)
    IconButton(onCreate, Modifier.testTag("type-add").semantics { contentDescription = label }) { AddIcon() }
}

/** Every class as a row of one card with how many instruments it holds; the default one is shown but not edited. */
@Composable fun TypesScreen(vm: AssetTypesViewModel, onEdit: (AssetType) -> Unit) {
    val state by vm.types.collectAsStateWithLifecycle()
    val current = state as? AssetTypesState.Ready
    if (current == null) {
        Text(stringResource(if (state == AssetTypesState.Failed) R.string.instrument_types_failed_short else R.string.instrument_types_loading_short))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "card") {
            GlassCard(Modifier.testTag("type-list")) {
                current.rows.forEachIndexed { index, type ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainer)
                    TypeRow(type, current.counts[type.id] ?: 0, if (type.isDefault) null else ({ onEdit(type) }))
                }
            }
        }
        item(key = "note") {
            Text(stringResource(R.string.instrument_types_note), Modifier.padding(start = 4.dp, top = 2.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun TypeRow(type: AssetType, count: Int, onEdit: (() -> Unit)?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(if (type.isDefault) "type-default" else "type-row-${type.id}")
        .then(if (onEdit != null) Modifier.clickable(role = Role.Button, onClick = onEdit) else Modifier)
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Name and tag take the room left, so the count and arrow sit at the end.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(typeLabel(type.name), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (type.isDefault) StatusTag(stringResource(R.string.instrument_type_default_tag), TagTone.NEUTRAL)
        }
        Text(pluralStringResource(R.plurals.instrument_type_row_count, count, count),
            style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
        if (onEdit != null) RowChevron()
    }
}
