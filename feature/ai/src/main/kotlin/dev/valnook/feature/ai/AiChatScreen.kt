package dev.valnook.feature.ai

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.draw.scale
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
fun AiChatScreen(vm: AiChatViewModel, onEdit: (prefill: String, operation: String) -> Unit, onOpenBill: ((Long) -> Unit)?,
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
        LaunchedEffect(ui.messages.size, ui.typing, ui.pendingShown) {
            val count = list.layoutInfo.totalItemsCount
            if (count > 0) list.animateScrollToItem(count - 1)
        }
        // Messages that arrive while the page is open come in once; those already there when it opened (or in a
        // conversation opened from the history) are simply shown, and nothing comes in again on scrolling back.
        val openedAt = remember { System.currentTimeMillis() }
        val arrived = remember { mutableSetOf<Long>() }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("ai-chat-messages"), list,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            if (ui.messages.isEmpty() && !ui.sending) item { Examples(vm) }
            // A message sent here keeps the key of the copy shown while it was stored: one item, no second entrance.
            items(ui.messages, key = { ui.keys[it.id] ?: it.id }) { message ->
                val animate = message.createdAtMs >= openedAt && arrived.add(message.id)
                Arrive(animate, fromRight = message.kind == AiMessageKind.USER) {
                    when (message.kind) {
                        AiMessageKind.USER -> UserMessage(message, ui, avatars.user)
                        AiMessageKind.ASSISTANT -> AssistantMessage(message, ui, avatars.ai, actions, vm, onOpenBill, onManual)
                        AiMessageKind.ERROR -> ErrorMessage(message, ui, avatars.ai, vm, onManual)
                    }
                }
            }
            ui.pending?.takeIf { ui.pendingShown }?.let { pending ->
                item(key = pending.key) { Arrive(true, fromRight = true) { PendingMessage(pending, ui, avatars.user) } }
            }
            // The reply on its way, as the AI's own bubble: only once the user's message is in, and a beat after it.
            if (ui.typing || (ui.pendingShown && ui.replyPending)) item(key = "typing") {
                Arrive(true, fromRight = false, delayMs = 180) {
                    Speaker(ui.aiNickname.ifBlank { "AI" }, avatars.ai, right = false) { Typing() }
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

/** A message coming in from its speaker's side: fading in, rising a little and growing to size, once. */
@Composable
private fun Arrive(animate: Boolean, fromRight: Boolean, delayMs: Int = 0, content: @Composable () -> Unit) {
    val progress = remember { androidx.compose.animation.core.Animatable(if (animate) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (animate) progress.animateTo(1f, androidx.compose.animation.core.tween(280, delayMillis = delayMs,
            easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    Box(Modifier.graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * 16.dp.toPx()
        scaleX = 0.94f + 0.06f * p
        scaleY = scaleX
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(if (fromRight) 1f else 0f, 1f)
    }) { content() }
}

/** Three dots rising in turn inside the AI's bubble while the reply is on its way. */
@Composable
private fun Typing() {
    val label = stringResource(R.string.ai_chat_working)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.semantics { contentDescription = label }.testTag("ai-chat-working")) {
        Row(Modifier.padding(horizontal = Space.md, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                val lift by transition.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 1200
                        0f at 0 + index * 150
                        1f at 200 + index * 150
                        0f at 400 + index * 150
                        0f at 1200
                    }), label = "dot$index")
                Box(Modifier.size(7.dp).graphicsLayer { translationY = -lift * 4.dp.toPx(); alpha = 0.45f + 0.55f * lift }
                    .clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
            }
        }
    }
}

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
            listOf(R.string.ai_chat_example_1, R.string.ai_chat_example_2, R.string.ai_chat_example_3).forEach { id ->
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
            horizontalAlignment = if (right) Alignment.End else Alignment.Start) {
            // The name sits close above what was said; the parts of a reply keep their own spacing.
            Text(name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Column(horizontalAlignment = if (right) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Space.xs), content = content)
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

/** The message just sent, as it will look once stored; its pictures come from the library meanwhile. */
@Composable
private fun PendingMessage(pending: AiPendingMessage, ui: AiChatUi, avatar: ImageBitmap?) {
    Speaker(ui.userNickname.ifBlank { stringResource(R.string.ai_chat_me) }, avatar, right = true) {
        if (pending.shared) Text(stringResource(R.string.ai_chat_shared), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (pending.pictures.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            pending.pictures.forEach { dev.valnook.feature.bills.BillImageThumb(it.key, 72.dp, {}) }
        }
        if (pending.text.isNotBlank()) Bubble(pending.text, mine = true, Modifier.testTag("ai-chat-pending"))
    }
}

@Composable
private fun UserMessage(message: AiChatMessage, ui: AiChatUi, avatar: ImageBitmap?) {
    Speaker(ui.userNickname.ifBlank { stringResource(R.string.ai_chat_me) }, avatar, right = true) {
        if (message.shared) Text(stringResource(R.string.ai_chat_shared), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("ai-chat-shared-${message.id}"))
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
    vm: AiChatViewModel, onOpenBill: ((Long) -> Unit)?, onManual: (String) -> Unit) {
    Speaker(ui.aiNickname.ifBlank { "AI" }, avatar, right = false) {
        message.reasoning?.let { Thinking(it, message.thinkingMs) }
        if (message.text.isNotBlank()) Bubble(message.text, mine = false, Modifier.testTag("ai-chat-reply-${message.id}"))
        message.drafts.forEach { card ->
            AiDraftCardView(card, ui.categories, ui.accounts, card.id in ui.busyCards, actions, money = ui.money[card.id])
        }
        val waiting = message.drafts.filter { it.state == AiDraftState.PENDING && it.draft.hasAmount && ui.money[it.id]?.missingRate != true }
        if (waiting.size >= 2) AiPrimaryButton(stringResource(R.string.ai_chat_confirm_all), { vm.confirmAll(message.drafts) },
            Modifier.testTag("ai-chat-confirm-all"))
        message.confirmedBills.forEach { billId ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_chat_already_saved), Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onOpenBill != null) AiTonalButton(stringResource(R.string.ai_chat_view_bill), { onOpenBill(billId) })
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
                AiTonalButton(stringResource(R.string.ai_chat_retry), vm::retry, Modifier.testTag("ai-chat-retry"))
            ManualButton(ui, vm, message, onManual)
        }
        if (message.usage?.calls ?: 0 > 0) Usage(message.usage)
    }
}

/** Opens the bill page with the user's last words before [message] in the note. */
@Composable
private fun ManualButton(ui: AiChatUi, vm: AiChatViewModel, message: AiChatMessage, onManual: (String) -> Unit) {
    val asked = ui.messages.lastOrNull { it.kind == AiMessageKind.USER && it.id < message.id }
    // Its pictures go along too, as long as they are still on this device.
    AiTonalButton(stringResource(R.string.ai_chat_manual), { vm.manual(asked?.text.orEmpty(), asked?.images.orEmpty(), onManual) },
        Modifier.testTag("ai-chat-manual-${message.id}"))
}

@Composable
private fun Thinking(text: String, ms: Long?) {
    var open by remember { mutableStateOf(false) }
    // No room above: it sits right under the name, as a reply's first line does; the tap area reaches down instead.
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button) { open = !open }.testTag("ai-chat-thinking")
        .padding(bottom = Space.xs)) {
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
            if (ui.sending) AiTonalButton(stringResource(R.string.ai_chat_cancel), vm::cancel, Modifier.testTag("ai-chat-cancel"))
            else AiPrimaryButton(stringResource(R.string.ai_chat_send), vm::send, Modifier.testTag("ai-chat-send"),
                enabled = (ui.input.isNotBlank() || ui.pictures.isNotEmpty()) && ui.picturesWorking == 0)
        }
        // At the very bottom, under the box: the thinking switch, and the small print beside it.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            ThinkingSwitch(ui, vm)
            Text(stringResource(R.string.ai_disclaimer), Modifier.weight(1f).testTag("ai-disclaimer"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (notice) PictureNotice({ notice = false }) { AiNotices.markPicturesSeen(context); notice = false; pick() }
}

/** Deep thinking for the next messages. Greyed out while it cannot be used or a message is on its way; a tap then says why. */
@Composable
private fun ThinkingSwitch(ui: AiChatUi, vm: AiChatViewModel) {
    val context = LocalContext.current
    val usable = ui.thinkingAvailable && !ui.sending
    val why = stringResource(R.string.ai_chat_thinking_unavailable)
    val color = MaterialTheme.colorScheme.onSurfaceVariant.let { if (usable) it else it.copy(alpha = 0.38f) }
    Row(Modifier.clip(RoundedCornerShape(8.dp)).toggleable(ui.thinking, role = Role.Switch) { on ->
            if (!ui.thinkingAvailable) Toast.makeText(context, why, Toast.LENGTH_SHORT).show() else vm.setThinking(on)
        }.testTag("ai-chat-thinking").padding(start = Space.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(stringResource(R.string.ai_chat_thinking_switch), style = MaterialTheme.typography.labelSmall, color = color)
        // A smaller switch: drawn at three quarters of its size in the room that takes.
        Switch(ui.thinking, null, Modifier.size(39.dp, 24.dp).scale(0.75f), enabled = usable)
    }
}

/** The one-time reminder before the first picture goes to the model, in the same notice as the other AI one. */
@Composable
fun PictureNotice(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    PolicyNoticeDialog(stringResource(R.string.ai_picture_notice_title), listOf(stringResource(R.string.ai_picture_notice)), null,
        stringResource(R.string.ai_chat_cancel), null, stringResource(R.string.ai_notice_ok), onDismiss, { onConfirm() },
        PolicyNoticeTags("ai-picture-notice", cancel = "ai-picture-notice-cancel", confirm = "ai-picture-notice-ok"))
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
