package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*

@Composable
fun AccountOrderScreen(vm: AccountOrderViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    Column(Modifier.fillMaxSize().padding(pageContentPadding())) {
        if (!state.loaded) {
            if (state.error) TextButton(vm::reload) { Text(stringResource(R.string.account_edit_load_failed)) }
            else CircularProgressIndicator()
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onBack, enabled = !state.busy) { Text(stringResource(R.string.account_order_cancel)) }
                Button(vm::save, enabled = !state.busy && !state.saved,
                    modifier = Modifier.testTag("account-order-save")) { Text(stringResource(R.string.account_save)) }
            }
            Text(stringResource(R.string.account_order_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.error) Row {
                Text(stringResource(R.string.account_order_error), Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(vm::discardAndReload) { Text(stringResource(R.string.account_order_reload)) }
            }
            ReorderList(state.accounts.map { ReorderItem(it.id.toString(), it.name, it.note) }, vm::reorder,
                stringResource(R.string.account_order_drag), stringResource(R.string.account_order_up),
                stringResource(R.string.account_order_down), Modifier.fillMaxWidth().weight(1f), !state.busy)
        }
    }
}
