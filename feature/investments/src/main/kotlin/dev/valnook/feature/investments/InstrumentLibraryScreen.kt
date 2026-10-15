package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import java.math.BigDecimal

@Composable
fun InstrumentLibrary(vm: InstrumentLibraryViewModel, onOpen: (Long) -> Unit, onTypes: () -> Unit, onCreate: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val current = state as? InstrumentLibraryState.Ready
    if (current == null) {
        Text(stringResource(if (state == InstrumentLibraryState.Failed) R.string.instrument_library_failed
            else R.string.instrument_library_loading))
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    var typeFilter by rememberSaveable { mutableStateOf<Long?>(null) }
    // A type deleted while selected falls back to all types.
    val selectedType = typeFilter?.takeIf { id -> current.types.any { it.id == id } }
    val matches = current.instruments.filter {
        (selectedType == null || it.instrument.typeId == selectedType) &&
            (it.instrument.name.contains(query, true) || it.instrument.symbol.contains(query, true))
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(onCreate, Modifier.weight(1f), enabled = current.hasTypes) { Text(stringResource(R.string.instrument_add)) }
            ActionButton(onTypes, Modifier.weight(1f)) { Text(stringResource(R.string.instrument_manage_types)) }
        } }
        if (!current.hasTypes) item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(stringResource(R.string.instrument_create_type_first))
                ActionButton(onTypes) { Text(stringResource(R.string.instrument_create_type)) }
            }
        }
        item { Field(stringResource(R.string.instrument_search), query, { query = it }) }
        if (current.hasTypes) item {
            val counts = current.instruments.groupingBy { it.instrument.typeId }.eachCount()
            Box(Modifier.testTag("instrument-type-filter")) {
                ChoiceField(stringResource(R.string.instrument_asset_class), selectedType?.toString().orEmpty(),
                    listOf("" to stringResource(R.string.instrument_filter_all_types, current.instruments.size)) +
                        current.types.map { it.id.toString() to
                            stringResource(R.string.instrument_filter_type_option, it.name, counts[it.id] ?: 0) },
                    { typeFilter = it.toLongOrNull() })
            }
        }
        if (matches.isEmpty()) item { EmptyState(stringResource(when {
            query.isNotBlank() -> R.string.instrument_no_match
            selectedType != null -> R.string.instrument_type_no_match
            else -> R.string.instrument_empty
        })) }
        items(matches, key = { it.instrument.id }) { summary ->
            val instrument = summary.instrument
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button) { onOpen(instrument.id) }.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    InstrumentIdentity(instrument, Modifier.weight(1f).alignByBaseline())
                    Text(stringResource(R.string.instrument_price_per_unit,
                        BigDecimal.valueOf(instrument.currentPriceE5, 5).stripTrailingZeros().toPlainString(),
                        instrument.currency.code), style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.alignByBaseline())
                }
                AccountProfitRow(money(summary.realized, instrument.currency),
                    money(summary.floating, instrument.currency),
                    displayedSign(summary.realized, instrument.currency.fraction_digits),
                    displayedSign(summary.floating, instrument.currency.fraction_digits))
            }
        }
    }
}
