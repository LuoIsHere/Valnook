package dev.valnook.feature.accounts

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

@Composable fun AccountDeletionDialog(commands: FinancialCommands, accountId: Long, cashId: Long?,
    onDismiss: () -> Unit, onDeleted: () -> Unit, discardDraft: Boolean = false) {
    var preview by remember(accountId, cashId) { mutableStateOf<AccountDeletionPreview?>(null) }
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val operationId = remember(retry) { UUID.randomUUID().toString() }
    LaunchedEffect(accountId, cashId, retry) { withContext(Dispatchers.Main.immediate) {
        var ticket: String? = null
        try {
            preview = null; input = ""; error = null
            preview = commands.previewAccountDeletion(accountId, cashId).also { ticket = it.ticket }
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = (failure as? DomainException)?.code?.name ?: "INTERNAL" }
        finally { ticket?.let { withContext(NonCancellable) { runCatching { commands.cancelAccountDeletion(it) } } } }
    } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!busy) onDismiss() }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { PopupBlurEffect(); Text(stringResource(R.string.account_delete_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = preview
                if (p == null && error == null) CircularProgressIndicator()
                if (p != null) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                    if (p.currencyCode != null && p.balanceMinor != null) Text(
                        DecimalRules.format_units(requireNotNull(p.balanceMinor), Currency.of(requireNotNull(p.currencyCode)).fraction_digits) + " " + p.currencyCode)
                    Text(stringResource(if (cashId == null) R.string.account_delete_parent_scope else R.string.account_delete_child_scope))
                    if (discardDraft) Text(stringResource(R.string.account_delete_draft_notice))
                    Text(stringResource(R.string.account_delete_counts, p.cashCount, p.entryCount, p.depositCount, p.tradeCount))
                    p.transfers.forEach { transfer -> Text(stringResource(R.string.account_delete_transfer,
                        transfer.name, DecimalRules.format_units(transfer.limitMinor, Currency.of(transfer.currencyCode).fraction_digits), transfer.currencyCode)) }
                    if (p.transfers.isNotEmpty()) Text(stringResource(R.string.account_delete_transfer_notice))
                    Text(stringResource(R.string.account_delete_code, p.code), style = MaterialTheme.typography.titleMedium)
                    Box(Modifier.testTag("account-delete-code")) { Field(stringResource(R.string.account_delete_code_input), input, { input = it.take(6) },
                        numeric = true, enabled = !busy) }
                }
                ErrorMessage(error)
                if (error != null && !busy) TextButton({ retry++ }) { Text(stringResource(R.string.account_delete_refresh)) }
            }
        },
        dismissButton = { TextButton({ if (!busy) onDismiss() }, enabled = !busy) { Text(stringResource(dev.valnook.core.designsystem.R.string.cancel)) } },
        confirmButton = { TextButton(onClick = {
            val p = preview ?: return@TextButton
            busy = true; error = null
            scope.launch(Dispatchers.Main.immediate) {
                try {
                    val confirmation = DeletionConfirmation(p.ticket, input)
                    commands.execute(if (cashId == null) DeleteAccount(operationId, accountId, p.revision, confirmation)
                        else DeleteBalanceAccount(operationId, accountId, cashId, p.revision, confirmation))
                    onDeleted()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = (failure as? DomainException)?.code?.name ?: "INTERNAL" }
                finally { busy = false }
            }
        }, enabled = !busy && preview != null && input == preview?.code,
            modifier = Modifier.testTag("account-delete-confirm")) { Text(stringResource(R.string.account_delete_confirm), color = if (!busy && preview != null && input == preview?.code) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)) } })
}
