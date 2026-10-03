package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*

@Composable
fun AccountInstrumentScreen(vm: AccountInstrumentViewModel, showPosition: @Composable (Long) -> Unit,
    onAdd: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountInstrumentState.Loading -> CircularProgressIndicator()
        AccountInstrumentState.Failed -> Text(stringResource(R.string.investment_account_failed))
        AccountInstrumentState.Missing -> EmptyState(stringResource(R.string.investment_account_missing))
        is AccountInstrumentState.Ready -> {
            val position = current.position
            if (position != null) showPosition(position.id)
            else LazyColumn(Modifier.fillMaxSize(), contentPadding = pageContentPadding(),
                verticalArrangement = Arrangement.spacedBy(Space.md)) {
                item { InstrumentIdentity(current.instrument) }
                item { Text(stringResource(R.string.investment_trade_history), style = MaterialTheme.typography.titleLarge) }
                item { EmptyState(stringResource(R.string.investment_no_trades)) }
                item { ActionButton(onAdd, Modifier.fillMaxWidth()) { Text(stringResource(R.string.investment_add_to_account)) } }
            }
        }
    }
}
