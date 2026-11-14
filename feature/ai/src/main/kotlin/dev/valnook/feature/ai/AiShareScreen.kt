package dev.valnook.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.LocalPageBottomSpace
import dev.valnook.designsystem.Space
import dev.valnook.domain.ai.AiDraftState
import dev.valnook.feature.bills.BillImageThumb

/**
 * The share page. [onEdit] opens the bill page with a draft, saved under the draft's operation; [onManual] opens it
 * with the share as it came; [onFinished] closes the page once every draft was dealt with, saying how many were saved.
 */
@Composable
fun AiShareScreen(vm: AiShareViewModel, onEdit: (prefill: String, operation: String) -> Unit, onManual: () -> Unit,
    onFinished: (saved: Int) -> Unit, onOpenBill: ((Long) -> Unit)? = null, onRates: (() -> Unit)? = null) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.phase) { if (ui.phase == AiSharePhase.MANUAL) onManual() }
    LaunchedEffect(ui.finished) { if (ui.finished) onFinished(ui.saved) }
    val actions = remember(vm) {
        AiDraftActions(onConfirm = { vm.confirm(it, onEdit) }, onEdit = { vm.edit(it, onEdit) }, onDiscard = vm::discard,
            onUndo = vm::undo, onOpenBill = onOpenBill, onKeepImages = vm::setKeepImages, onRates = onRates)
    }
    Column(Modifier.fillMaxSize().testTag("ai-share")) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (ui.host.isNotEmpty() && ui.phase != AiSharePhase.MANUAL) item {
                Text(stringResource(R.string.ai_share_sent_to, ui.host), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ai-share-host"))
            }
            item { Shared(ui) }
            when (ui.phase) {
                AiSharePhase.PREPARING, AiSharePhase.MANUAL -> item { Working(null) }
                AiSharePhase.READING -> item { Working(vm::cancel) }
                AiSharePhase.FAILED -> item {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.testTag("ai-share-error")) {
                            Text(ui.problem?.let { problemText(it, ui.host) }.orEmpty(), Modifier.padding(Space.md),
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Row {
                            TextButton(vm::read, Modifier.testTag("ai-share-retry")) { Text(stringResource(R.string.ai_chat_retry)) }
                            TextButton(onManual, Modifier.testTag("ai-share-manual")) { Text(stringResource(R.string.ai_chat_manual)) }
                        }
                    }
                }
                AiSharePhase.RESULT -> {
                    if (ui.message.isNotBlank()) item {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Text(AiText.plain(ui.message), Modifier.padding(horizontal = Space.md, vertical = Space.sm))
                        }
                    }
                    items(ui.cards, key = { it.id }) { card ->
                        AiDraftCardView(card, ui.categories, ui.accounts, card.id in ui.busyCards, actions, money = ui.money[card.id])
                    }
                    val waiting = ui.cards.count { it.state == AiDraftState.PENDING && it.draft.hasAmount && ui.money[it.id]?.missingRate != true }
                    if (waiting >= 2) item {
                        FilledTonalButton(vm::confirmAll, Modifier.testTag("ai-share-confirm-all")) { Text(stringResource(R.string.ai_chat_confirm_all)) }
                    }
                    // Nothing read from it: the bill page, with the share as it came.
                    if (ui.cards.isEmpty()) item {
                        TextButton(onManual, Modifier.testTag("ai-share-manual")) { Text(stringResource(R.string.ai_chat_manual)) }
                    }
                    item { UsageLine(ui.usage) }
                }
            }
            if (ui.saveFailed) item {
                Text(stringResource(R.string.ai_chat_save_failed), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.ai_disclaimer), Modifier.fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = Space.sm, bottom = Space.sm + LocalPageBottomSpace.current).testTag("ai-disclaimer"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Shared(ui: AiShareUi) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.testTag("ai-share-content")) {
        if (ui.pictures.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            items(ui.pictures, key = { it.key }) { BillImageThumb(it.key, 72.dp, {}) }
        }
        if (ui.picturesFailed > 0) Text(stringResource(R.string.ai_chat_picture_failed), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        if (ui.text.isNotEmpty()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(ui.text, Modifier.padding(horizontal = Space.md, vertical = Space.sm), maxLines = 8,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Working(onCancel: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.testTag("ai-share-working")) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(stringResource(R.string.ai_chat_working), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        onCancel?.let { TextButton(it, Modifier.testTag("ai-share-cancel")) { Text(stringResource(R.string.ai_chat_cancel)) } }
    }
}

@Composable
private fun UsageLine(usage: dev.valnook.domain.ai.AiTurnUsage?) {
    val number = java.text.NumberFormat.getIntegerInstance()
    val text = if (usage == null || !usage.known) stringResource(R.string.ai_chat_usage_unknown) else
        stringResource(R.string.ai_chat_usage, number.format(usage.total ?: ((usage.input ?: 0) + (usage.output ?: 0))),
            usage.input?.let(number::format) ?: "?", usage.output?.let(number::format) ?: "?")
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
