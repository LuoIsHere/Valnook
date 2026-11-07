package dev.valnook.feature.investments

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.valnook.designsystem.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Says what deleting the instrument removes and what it keeps. With trades it asks for the preview's code, as
 * deleting an account does; without, one tap is enough.
 */
@Composable fun InstrumentDeletionDialog(commands: FinancialCommands, instrumentId: Long,
    onDismiss: () -> Unit, onDeleted: () -> Unit) {
    var preview by remember(instrumentId) { mutableStateOf<InstrumentDeletionPreview?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val operationId = remember(retry) { UUID.randomUUID().toString() }
    LaunchedEffect(instrumentId, retry) { withContext(Dispatchers.Main.immediate) {
        var ticket: String? = null
        try {
            preview = null; input = ""; error = null
            preview = commands.previewInstrumentDeletion(instrumentId).also { ticket = it.ticket }
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? DomainException)?.code?.name ?: "INTERNAL" }
        finally { ticket?.let { withContext(NonCancellable) { runCatching { commands.cancelInstrumentDeletion(it) } } } }
    } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!busy) onDismiss() }
    val p = preview
    val ready = !busy && p != null && (!p.needsCode || input == p.code)
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { PopupBlurEffect(); Text(stringResource(R.string.instrument_delete)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("instrument-delete-dialog"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p == null && error == null) CircularProgressIndicator()
                if (p != null && !p.needsCode) Text(stringResource(R.string.instrument_delete_simple, p.name))
                if (p != null && p.needsCode) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.instrument_delete_scope, p.accountCount, p.tradeCount, p.priceCount))
                    if (p.holdingQuantityE8 > 0) Text(stringResource(R.string.instrument_delete_value,
                        DecimalRules.format_display(p.marketValue, Currency.of(p.currencyCode).fraction_digits) + " " + p.currencyCode))
                    Text(stringResource(R.string.instrument_delete_cash, p.cashEntryCount))
                    Text(stringResource(R.string.instrument_delete_code, p.code), style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.testTag("instrument-delete-code")) {
                        Field(stringResource(R.string.instrument_delete_code_input), input, { input = it.take(6) },
                            numeric = true, enabled = !busy)
                    }
                }
                ErrorMessage(error)
                if (error != null && !busy) TextButton({ retry++ }) { Text(stringResource(R.string.instrument_delete_refresh)) }
            }
        },
        dismissButton = { TextButton({ if (!busy) onDismiss() }, enabled = !busy) {
            Text(stringResource(dev.valnook.core.designsystem.R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            val current = preview ?: return@TextButton
            busy = true; error = null
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    commands.execute(DeleteInstrument(operationId, instrumentId, current.revision,
                        DeletionConfirmation(current.ticket, input)))
                    onDeleted()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = (failure as? DomainException)?.code?.name ?: "INTERNAL" }
                finally { busy = false }
            }
        }, enabled = ready, modifier = Modifier.testTag("instrument-delete-confirm")) {
            Text(stringResource(R.string.instrument_delete_confirm),
                color = if (ready) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
        } })
}
