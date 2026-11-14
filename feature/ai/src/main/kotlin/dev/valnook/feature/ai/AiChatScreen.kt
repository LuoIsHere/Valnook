package dev.valnook.feature.ai

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.ai.*
import dev.valnook.feature.bills.BillImageCodec
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the chat page puts in the title bar. */
data class AiChatToolbarState(val title: String?, val onNew: () -> Unit, val onHistory: () -> Unit, val canNew: Boolean)

/**
 * The AI chat page. [onEdit] opens the bill page with a draft filled in, saved under the draft's operation;
 * [onManual] opens it with words and pictures the model could not turn into drafts.
 */
@Composable
fun AiChatScreen(vm: AiChatViewModel, onEdit: (prefill: String, operation: String) -> Unit, onOpenBill: (Long) -> Unit,
    onManual: (prefill: String) -> Unit, onToolbar: (AiChatToolbarState?) -> Unit = {}, onRates: (() -> Unit)? = null) {
    val ui by vm.state.collectAsStateWithLifecycle()
    var history by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }
    val canNew = ui.conversationId != null && !ui.sending
    LaunchedEffect(ui.title, canNew) {
        onToolbar(AiChatToolbarState(ui.title, vm::newConversation, { history = true }, canNew))
    }
    DisposableEffect(Unit) { onDispose { onToolbar(null) } }
    if (!ui.loaded) return
    val avatars = rememberAvatars()
    val actions = remember(vm) {
        AiDraftActions(onConfirm = { vm.confirm(it, onEdit) }, onEdit = { vm.edit(it, onEdit) }, onDiscard = vm::discard,
            onUndo = vm::undo, onOpenBill = onOpenBill, onKeepImages = vm::setKeepImages, onRates = onRates)
    }
    Column(Modifier.fillMaxSize().testTag("ai-chat")) {
        val list = rememberLazyListState()
        // New messages, and the working line, scroll into view.
        LaunchedEffect(ui.messages.size, ui.sending) {
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) list.animateScrollToItem(count - 1)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai-chat-messages"), list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (ui.messages.isEmpty() && !ui.sending) item { Examples(vm) }
            items(ui.messages, key = { it.id }) { message ->
                when (message.kind) {
                    AiMessageKind.USER -> UserMessage(message, ui, avatars.user)
                    AiMessageKind.ASSISTANT -> AssistantMessage(message, ui, avatars.ai, actions, vm, onOpenBill, onManual)
                    AiMessageKind.ERROR -> ErrorMessage(message, ui, avatars.ai, vm, onManual)
                }
            }
            if (ui.sending) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier.testTag("ai-chat-working")) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.ai_chat_working), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (ui.cardFailed) Text(stringResource(R.string.ai_chat_save_failed), Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp).clickable { vm.dismissCardError() }.testTag("ai-chat-save-failed"),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        InputBar(ui, vm)
    }
    if (history) HistorySheet(ui, vm) { history = false }
}

private class Avatars(val user: ImageBitmap?, val ai: ImageBitmap?)

@Composable
private fun rememberAvatars(): Avatars {
    val context = LocalContext.current
    return remember {
        val store = AiAvatars(AiAvatars.directory(context))
        fun load(who: AiAvatar) = store.file(who)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
        Avatars(load(AiAvatar.USER), load(AiAvatar.AI))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Examples(vm: AiChatViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = Space.lg).testTag("ai-chat-examples"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Text(stringResource(R.string.ai_chat_examples), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.CenterHorizontally)) {
            listOf(R.string.ai_chat_example_1, R.string.ai_chat_example_2).forEach { id ->
                val text = stringResource(id)
                SuggestionChip({ vm.setInput(text) }, { Text(text) })
            }
        }
    }
}

@Composable
private fun Speaker(name: String, image: ImageBitmap?, right: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top) {
        if (!right) AiAvatarImage(image, name, Modifier.size(32.dp).clip(CircleShape))
        Column(Modifier.weight(1f, fill = false).padding(horizontal = Space.sm),
            horizontalAlignment = if (right) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
        if (right) AiAvatarImage(image, name, Modifier.size(32.dp).clip(CircleShape))
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp),
        color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(AiText.plain(text), Modifier.padding(horizontal = Space.md, vertical = Space.sm), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun UserMessage(message: AiChatMessage, ui: AiChatUi, avatar: ImageBitmap?) {
    Speaker(ui.userNickname.ifBlank { stringResource(R.string.ai_chat_me) }, avatar, right = true) {
        if (message.pictures.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            message.pictures.forEach { picture ->
                val image = remember(picture) { BitmapFactory.decodeByteArray(picture.bytes, 0, picture.bytes.size)?.asImageBitmap() }
                if (image != null) Image(image, null, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
            }
        }
        if (message.text.isNotBlank()) Bubble(message.text, mine = true, Modifier.testTag("ai-chat-user-${message.id}"))
    }
}

@Composable
private fun AssistantMessage(message: AiChatMessage, ui: AiChatUi, avatar: ImageBitmap?, actions: AiDraftActions,
    vm: AiChatViewModel, onOpenBill: (Long) -> Unit, onManual: (String) -> Unit) {
    Speaker(ui.aiNickname.ifBlank { "AI" }, avatar, right = false) {
        message.reasoning?.let { Thinking(it, message.thinkingMs) }
        if (message.text.isNotBlank()) Bubble(message.text, mine = false, Modifier.testTag("ai-chat-reply-${message.id}"))
        message.drafts.forEach { card ->
            AiDraftCardView(card, ui.categories, ui.accounts, card.id in ui.busyCards, actions, money = ui.money[card.id])
        }
        val waiting = message.drafts.filter { it.state == AiDraftState.PENDING && it.draft.hasAmount && ui.money[it.id]?.missingRate != true }
        if (waiting.size >= 2) FilledTonalButton({ vm.confirmAll(message.drafts) }, Modifier.testTag("ai-chat-confirm-all")) {
            Text(stringResource(R.string.ai_chat_confirm_all))
        }
        message.confirmedBills.forEach { billId ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_chat_already_saved), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ onOpenBill(billId) }) { Text(stringResource(R.string.ai_chat_view_bill)) }
            }
        }
        if (message.noDrafts) ManualButton(ui, vm, message, onManual)
        Usage(message.usage)
    }
}

@Composable
private fun ErrorMessage(message: AiChatMessage, ui: AiChatUi, avatar: ImageBitmap?, vm: AiChatViewModel, onManual: (String) -> Unit) {
    Speaker(ui.aiNickname.ifBlank { "AI" }, avatar, right = false) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.testTag("ai-chat-error-${message.problem?.name?.lowercase() ?: "cancelled"}")) {
            Text(message.problem?.let { problemText(it, ui.host) } ?: stringResource(R.string.ai_chat_cancelled),
                Modifier.padding(horizontal = Space.md, vertical = Space.sm), color = MaterialTheme.colorScheme.onErrorContainer)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (ui.canRetry && ui.messages.lastOrNull()?.id == message.id)
                TextButton(vm::retry, Modifier.testTag("ai-chat-retry")) { Text(stringResource(R.string.ai_chat_retry)) }
            ManualButton(ui, vm, message, onManual)
        }
        if (message.usage?.calls ?: 0 > 0) Usage(message.usage)
    }
}

/** Opens the bill page with the user's last words before [message] in the note. */
@Composable
private fun ManualButton(ui: AiChatUi, vm: AiChatViewModel, message: AiChatMessage, onManual: (String) -> Unit) {
    val asked = ui.messages.lastOrNull { it.kind == AiMessageKind.USER && it.id < message.id }
    TextButton({ vm.manual(asked?.text.orEmpty(), emptyList(), onManual) }, Modifier.testTag("ai-chat-manual-${message.id}")) {
        Text(stringResource(R.string.ai_chat_manual))
    }
}

@Composable
private fun Thinking(text: String, ms: Long?) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = !open }.testTag("ai-chat-thinking")
        .padding(vertical = Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (ms != null) stringResource(R.string.ai_chat_thinking_time, ((ms + 500) / 1000).toString())
                else stringResource(R.string.ai_chat_thinking), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            FaIcon(if (open) FaIcons.chevronDown else FaIcons.chevronRight, null, Modifier.padding(start = Space.xs).size(12.dp),
                MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Usage(usage: AiTurnUsage?) {
    val number = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    fun n(value: Long?) = value?.let(number::format) ?: "?"
    val text = if (usage == null || !usage.known) stringResource(R.string.ai_chat_usage_unknown) else buildString {
        append(stringResource(R.string.ai_chat_usage, n(usage.total ?: ((usage.input ?: 0) + (usage.output ?: 0))), n(usage.input), n(usage.output)))
        // Resource strings lose leading spaces, so the separator is added here.
        usage.cached?.takeIf { it > 0 }?.let { append(" · ").append(stringResource(R.string.ai_chat_usage_cached, number.format(it))) }
        if (usage.calls > 1) append(" · ").append(stringResource(R.string.ai_chat_usage_calls, usage.calls))
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("ai-chat-usage"))
}

@Composable
private fun InputBar(ui: AiChatUi, vm: AiChatViewModel) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    var notice by remember { mutableStateOf(false) }
    val room = (AiRules.MAX_IMAGES - ui.pictures.size - ui.picturesWorking).coerceAtLeast(0)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AiRules.MAX_IMAGES)) { uris ->
        vm.addPictures(uris.map { uri -> suspend { BillImageCodec.prepare(resolver, uri) } })
    }
    fun pick() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    // Clear of the system bar; with the keyboard open the page already ends above it.
    val keyboardOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val bottom = if (keyboardOpen) 0.dp else LocalPageBottomSpace.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = Space.sm, bottom = Space.sm + bottom),
        verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (ui.pictures.isNotEmpty() || ui.picturesWorking > 0) LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            items(ui.pictures, key = { it.key }) { picture ->
                Box {
                    dev.valnook.feature.bills.BillImageThumb(picture.key, 56.dp, {})
                    val remove = stringResource(R.string.ai_chat_picture_remove)
                    Box(Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape)
                        .clickable(role = Role.Button) { vm.removePicture(picture.key) }.semantics { contentDescription = remove }
                        .testTag("ai-chat-picture-remove"), contentAlignment = Alignment.Center) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface) {
                            Text("×", Modifier.padding(horizontal = 5.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (ui.picturesWorking > 0) item { Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) } }
        }
        if (ui.pictureFailed) Text(stringResource(R.string.ai_chat_picture_failed), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (ui.features.pictures) {
                val label = stringResource(R.string.ai_chat_pictures)
                IconButton({ if (AiNotices.picturesSeen(context)) pick() else notice = true }, enabled = room > 0 && !ui.sending,
                    modifier = Modifier.semantics { contentDescription = label }.testTag("ai-chat-add-pictures")) {
                    FaIcon(FaIcons.image, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(ui.input, vm::setInput, Modifier.weight(1f).testTag("ai-chat-input"),
                placeholder = { Text(stringResource(R.string.ai_chat_input_hint)) }, shape = RoundedCornerShape(20.dp), maxLines = 5)
            if (ui.sending) TextButton(vm::cancel, Modifier.testTag("ai-chat-cancel")) { Text(stringResource(R.string.ai_chat_cancel)) }
            else FilledTonalButton(vm::send, enabled = (ui.input.isNotBlank() || ui.pictures.isNotEmpty()) && ui.picturesWorking == 0,
                modifier = Modifier.testTag("ai-chat-send")) { Text(stringResource(R.string.ai_chat_send)) }
        }
        // At the very bottom, under the box, as small print.
        Text(stringResource(R.string.ai_disclaimer), Modifier.fillMaxWidth().testTag("ai-disclaimer"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (notice) PictureNotice({ notice = false }) { AiNotices.markPicturesSeen(context); notice = false; pick() }
}

/** The one-time reminder before the first picture goes to the model. */
@Composable
fun PictureNotice(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismiss, confirmButton = { TextButton(onConfirm, Modifier.testTag("ai-picture-notice-ok")) {
        Text(stringResource(R.string.ai_notice_ok)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.ai_chat_cancel)) } },
        title = { Text(stringResource(R.string.ai_picture_notice_title)) },
        text = { Text(stringResource(R.string.ai_picture_notice)) }, modifier = Modifier.testTag("ai-picture-notice"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(ui: AiChatUi, vm: AiChatViewModel, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale)
    val number = NumberFormat.getIntegerInstance(locale)
    ModalBottomSheet(onDismiss, Modifier.testTag("ai-chat-history")) {
        if (ui.conversations.isEmpty()) Text(stringResource(R.string.ai_chat_history_empty), Modifier.padding(Space.cardInset),
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(bottom = Space.xl)) {
            items(ui.conversations, key = { it.id }) { conversation ->
                Row(Modifier.fillMaxWidth().clickable { vm.openConversation(conversation.id); onDismiss() }
                    .testTag("ai-chat-history-${conversation.id}").padding(horizontal = Space.cardInset, vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(conversation.title, style = MaterialTheme.typography.bodyLarge,
                            color = if (conversation.id == ui.conversationId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text(Instant.ofEpochMilli(conversation.lastAtMs).atZone(ZoneId.systemDefault()).format(format) + " · " +
                            stringResource(R.string.ai_chat_tokens, number.format(conversation.tokens)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val delete = stringResource(R.string.ai_chat_delete)
                    IconButton({ vm.deleteConversation(conversation.id) }, Modifier.semantics { contentDescription = delete }
                        .testTag("ai-chat-history-delete-${conversation.id}")) {
                        FaIcon(FaIcons.trash, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
