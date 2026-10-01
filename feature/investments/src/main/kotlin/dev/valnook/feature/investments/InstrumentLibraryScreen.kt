package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val matches = current.instruments.filter { it.instrument.name.contains(query, true) || it.instrument.symbol.contains(query, true) }
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
        if (matches.isEmpty()) item { EmptyState(stringResource(if (query.isBlank()) R.string.instrument_empty else R.string.instrument_no_match)) }
        items(matches, key = { it.instrument.id }) { summary ->
            val instrument = summary.instrument
            Column(Modifier.fillMaxWidth().clickable { onOpen(instrument.id) }.padding(vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text(instrument.name, style = MaterialTheme.typography.titleMedium, maxLines = 2,
                            overflow = TextOverflow.Ellipsis)
                        Text(instrument.symbol.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    Text(stringResource(R.string.instrument_price_per_unit,
                        BigDecimal.valueOf(instrument.currentPriceE5, 5).stripTrailingZeros().toPlainString(),
                        instrument.currency.code), style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.End, maxLines = 2)
                }
                AccountProfitRow(stringResource(R.string.investment_realized_value, money(summary.realized, instrument.currency)),
                    stringResource(R.string.investment_unrealized_value, money(summary.floating, instrument.currency)),
                    summary.realized?.signum() ?: 0, summary.floating?.signum() ?: 0)
            }
        }
    }
}
