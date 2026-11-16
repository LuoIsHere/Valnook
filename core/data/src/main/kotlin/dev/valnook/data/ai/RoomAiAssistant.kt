package dev.valnook.data.ai

import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.util.UUID

/** Sent pictures, read from the bill picture library. */
interface AiPictureSource {
    /** The picture as the model gets it: a JPEG no larger than [AiRules.IMAGE_EDGE], without metadata; null when gone. */
    suspend fun forModel(key: String): ByteArray?
    /** A small copy kept with the conversation; null when the picture cannot be read. */
    suspend fun thumbnail(key: String): ByteArray?
}

/** Keeps pictures of waiting drafts in the library, which otherwise deletes pictures no bill holds. */
interface AiPicturePins {
    fun pin(keys: Collection<String>)
    /** Lets go of [keys]; those no bill or form holds any more are deleted. */
    suspend fun release(keys: Collection<String>)
}

/**
 * Conversations and shares against the saved connection. Requests use the same beginning as the prompt preview; every
 * call's tokens are added to the month's usage, whether the round ends in drafts, an error or a cancel.
 */
class RoomAiAssistant(
    private val db: ValnookDatabase,
    private val chatDb: AiChatDatabase,
    private val service: AiService,
    private val keys: AiKeyStore,
    private val clock: Clock,
    private val english: () -> Boolean,
    private val pictures: AiPictureSource,
    private val pins: AiPicturePins,
    private val client: AiClient = AiClient(),
) : AiAssistant {
    private val dao = chatDb.chat()

    override fun observeConversations(): Flow<List<AiConversation>> =
        combine(dao.observeConversations(), dao.observePendingConversations()) { list, pending ->
            val counts = pending.groupingBy { it }.eachCount()
            list.map { AiConversation(it.id, it.title, it.created_at_ms, it.last_at_ms, it.tokens, counts[it.id] ?: 0) }
        }

    override fun observeMessages(conversationId: Long): Flow<List<AiChatMessage>> = combine(
        dao.observeMessages(conversationId), dao.observePictures(conversationId), dao.observeDrafts(conversationId),
    ) { messages, pictureRows, drafts ->
        val pictureBy = pictureRows.groupBy { it.message_id }
        val draftsBy = drafts.groupBy { it.message_id }
        messages.map { m ->
            AiChatMessage(m.id, AiMessageKind.valueOf(m.kind), m.text,
                pictureBy[m.id].orEmpty().sortedBy { it.position }.map { AiThumbnail(it.thumb) },
                m.reasoning, m.thinking_ms,
                if (m.kind == AiMessageKind.USER.name) null
                else AiTurnUsage(m.input_tokens, m.output_tokens, m.total_tokens, m.cached_tokens, m.calls),
                m.problem?.let { name -> AiProblem.entries.firstOrNull { it.name == name } },
                m.no_drafts, draftsBy[m.id].orEmpty().mapNotNull { it.card() },
                AiConversations.decodeIds(m.confirmed_bills), m.created_at_ms, m.shared,
                pictureBy[m.id].orEmpty().sortedBy { it.position }.mapNotNull { row ->
                    (runCatching { AiJson.format.parseToJsonElement(row.image) }.getOrNull() as? JsonObject)
                        ?.let(AiConversations::decodeImage)
                })
        }
    }

    override fun observePendingDrafts(): Flow<Boolean> = dao.observeAnyPending()

    override fun observeUsage(): Flow<AiUsageSummary> =
        dao.observeUsageSince(ZonedDateTime.now(clock).toLocalDate().withDayOfMonth(1).atStartOfDay(clock.zone).toInstant().toEpochMilli())
            .map { AiUsageSummary(it.requests, it.tokens, it.input, it.output) }

    override suspend fun conversationToContinue(): Long? {
        val latest = dao.latestConversation() ?: return null
        return latest.id.takeIf { clock.millis() - latest.last_at_ms < AiRules.CONVERSATION_IDLE_MS }
    }

    override suspend fun send(conversationId: Long?, text: String, pictures: List<BillImage>, thinking: Boolean?,
        onStored: (Long, Long) -> Unit): Long {
        check(service.observeFeatures().first().chatEntry)
        return sendMessage(conversationId, text, pictures, shared = false, thinking, onStored)
    }

    override suspend fun sendShared(conversationId: Long?, text: String, pictures: List<BillImage>,
        onStored: (Long, Long) -> Unit): Long {
        check(service.observeFeatures().first().shareAllows(pictures.isNotEmpty()))
        return sendMessage(conversationId, text, pictures, shared = true, null, onStored)
    }

    /** Whether AI reads this kind of share: text by one switch, pictures by the other. */
    private fun AiFeatures.shareAllows(pictures: Boolean) = enabled && (if (pictures) shareImage else shareText)

    /** Stores the message first, so the page has it at once; what the request is built from is read after. */
    private suspend fun sendMessage(conversationId: Long?, text: String, pictures: List<BillImage>, shared: Boolean,
        thinking: Boolean?, onStored: (Long, Long) -> Unit): Long {
        val now = clock.millis()
        val thumbs = pictures.map { this.pictures.thumbnail(it.key) ?: ByteArray(0) }
        val (id, messageId) = chatDb.withTransaction {
            val id = conversationId?.takeIf { dao.conversation(it) != null }
                ?: dao.insertConversation(AiConversationEntity(title = AiConversations.title(text, pictures.size, english()),
                    created_at_ms = now, last_at_ms = now, tokens = 0))
            val messageId = dao.insertMessage(message(id, AiMessageKind.USER, text.trim(), now).copy(shared = shared))
            if (pictures.isNotEmpty()) dao.insertPictures(pictures.mapIndexed { index, image ->
                AiPictureEntity(messageId, index, image.key, AiConversations.encodeImage(image).toString(), thumbs[index])
            })
            dao.touch(id, now, 0)
            id to messageId
        }
        onStored(id, messageId)
        answer(id, context(), thinking)
        return id
    }

    override suspend fun retry(conversationId: Long, thinking: Boolean?) {
        val messages = dao.messages(conversationId)
        val last = messages.lastOrNull { it.kind == AiMessageKind.USER.name } ?: return
        // Only an unanswered message is sent again: everything after it is an error or a cancel.
        val after = messages.filter { it.id > last.id }
        if (after.any { it.kind == AiMessageKind.ASSISTANT.name }) return
        val context = context()
        check(if (last.shared) context.features.shareAllows(dao.pictures(last.id).isNotEmpty()) else context.features.chatEntry)
        dao.deleteMessages(after.map { it.id })
        answer(conversationId, context, thinking)
    }

    /** Asks about the conversation's last message and stores what came back; [asked] as for [send]'s thinking. */
    private suspend fun answer(conversationId: Long, context: Context, asked: Boolean?) {
        val messages = dao.messages(conversationId)
        val user = messages.last { it.kind == AiMessageKind.USER.name }
        val drafts = dao.drafts(conversationId)
        val counts = messages.filter { it.kind == AiMessageKind.USER.name && it.id != user.id }
            .associate { it.id to dao.pictures(it.id).size }
        val images = dao.pictures(user.id).mapNotNull { row ->
            (runCatching { AiJson.format.parseToJsonElement(row.image) }.getOrNull() as? JsonObject)?.let(AiConversations::decodeImage)
        }
        val jpegs = images.mapNotNull { pictures.forModel(it.key) }
        val history = AiConversations.history(messages.filter { it.id < user.id }, drafts, counts)
        // A share is always bookkeeping: thinking off and the draft tool required, as before it joined the chat.
        val shared = user.shared
        val thinking = !shared && context.features.thinkingAvailable && (asked ?: context.features.thinking)
        val words = (if (shared) AiPrompt.SHARE_INTRO + "\n\n" else "") + AiConversations.userText(user.text, jpegs.size, earlier = false)
        val conversation = history + AiConversations.userMessage(words, jpegs)
        val system = context.system(english())
        val timeout = when { thinking -> AiRules.TIMEOUT_THINKING_MS; jpegs.isNotEmpty() -> AiRules.TIMEOUT_IMAGE_MS; else -> AiRules.TIMEOUT_TEXT_MS }
        val source = if (shared) "SHARE" else "CHAT"
        val round = Round()
        var force = shared
        try {
            // Pictures and shares are read for the currency they show.
            val (reply, calls) = askForDrafts(context, round, currencyExpected = shared || jpegs.isNotEmpty()) { key ->
                try { client.chat(context.settings.connection, key, AiPrompt.billRequest(system, conversation, thinking, force), thinking, timeout) }
                catch (error: AiException) {
                    // Some services refuse a required tool; asking without that is the next best thing.
                    if (!force || error.problem != AiProblem.REJECTED) throw error
                    round.calls++
                    force = false
                    client.chat(context.settings.connection, key, AiPrompt.billRequest(system, conversation, thinking, false), thinking, timeout)
                }
            }
            store(conversationId, reply, calls, round, images, drafts, source)
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { storeError(conversationId, null, round, source) }
            throw cancelled
        } catch (error: AiException) {
            storeError(conversationId, error.problem, round, source)
        }
    }

    /**
     * Calls the model until its drafts can be read, asking once more when they cannot ([AiRules.FORMAT_RETRIES]).
     * Returns the reply and its readable draft calls. A reply the output limit cut short is not asked for again, as
     * the same limit would cut it again; one with neither words nor drafts is an error, not an answer.
     */
    private suspend fun askForDrafts(context: Context, round: Round, currencyExpected: Boolean,
        call: suspend (String?) -> AiReply): Pair<AiReply, List<AiDraftCall>> {
        val key = savedKey()
        val reader = context.reader(currencyExpected)
        repeat(AiRules.FORMAT_RETRIES + 1) {
            val started = clock.millis()
            val reply = call(key)
            round.calls++
            round.add(reply.usage)
            round.thinkingMs = if (reply.reasoning != null) clock.millis() - started else null
            if (reply.finishReason == "length") throw AiException(AiProblem.TRUNCATED)
            val calls = reply.toolCalls.filter { it.name == AiPrompt.BILL_TOOL }
            if (calls.isEmpty() && reply.content.isBlank()) throw AiException(AiProblem.EMPTY_REPLY)
            val read = calls.map { runCatching { reader.read(it.arguments) }.getOrNull() }
            if (read.none { it == null }) return reply to read.filterNotNull()
        }
        throw AiException(AiProblem.UNREADABLE_DRAFTS)
    }

    private suspend fun store(conversationId: Long, reply: AiReply, calls: List<AiDraftCall>, round: Round,
        images: List<BillImage>, before: List<AiDraftEntity>, source: String) {
        val now = clock.millis()
        val latest = before.groupBy { it.label }.mapValues { (_, rows) -> rows.maxBy { it.id } }
        val proposed = calls.flatMap { it.drafts }
        val assigned = AiConversations.assignPictures(images, proposed.size)
        val pinned = mutableListOf<String>()
        val released = mutableListOf<String>()
        chatDb.withTransaction {
            val confirmed = mutableListOf<Long>()
            val messageId = dao.insertMessage(AiMessageEntity(conversation_id = conversationId, kind = AiMessageKind.ASSISTANT.name,
                text = AiConversations.replyText(reply.content, calls.map { it.message }),
                raw = AiJson.format.encodeToString(JsonObject.serializer(), reply.message),
                reasoning = reply.reasoning, thinking_ms = round.thinkingMs, input_tokens = round.input, output_tokens = round.output,
                total_tokens = round.total, cached_tokens = round.cached, calls = round.calls, problem = null,
                no_drafts = proposed.isEmpty(), confirmed_bills = "[]", created_at_ms = now))
            var fresh = 0
            proposed.forEachIndexed { index, (label, draft) ->
                val old = label?.let(latest::get)
                if (old?.state == AiDraftState.CONFIRMED.name) { old.bill_id?.let(confirmed::add); return@forEachIndexed }
                val ownPictures = assigned.getOrElse(index) { emptyList() }
                val oldPictures = old?.takeIf { it.state == AiDraftState.PENDING.name }?.let { AiConversations.decodeImages(it.images) }.orEmpty()
                val cardPictures = ownPictures.ifEmpty { oldPictures }
                val newLabel = if (old?.state == AiDraftState.PENDING.name) {
                    dao.setDraftState(old.id, AiDraftState.REPLACED.name, null, old.operation_id)
                    released += oldPictures.map { it.key }
                    old.label
                } else AiConversations.nextLabel(before, fresh++)
                dao.insertDraft(AiDraftEntity(conversation_id = conversationId, message_id = messageId, label = newLabel,
                    payload = AiConversations.encodeDraft(draft), state = AiDraftState.PENDING.name, bill_id = null,
                    operation_id = UUID.randomUUID().toString(), images = AiConversations.encodeImages(cardPictures),
                    keep_images = true))
                pinned += cardPictures.map { it.key }
            }
            if (confirmed.isNotEmpty()) dao.setConfirmedBills(messageId, AiConversations.encodeIds(confirmed))
            dao.touch(conversationId, now, round.total ?: 0)
        }
        // Pinned before letting go, so a picture moving from a replaced card to its successor is never unheld.
        pins.pin(pinned)
        pins.release(released)
        recordUsage(source, round)
    }

    private suspend fun storeError(conversationId: Long, problem: AiProblem?, round: Round, source: String) {
        val now = clock.millis()
        dao.insertMessage(message(conversationId, AiMessageKind.ERROR, "", now).copy(problem = problem?.name,
            input_tokens = round.input, output_tokens = round.output, total_tokens = round.total,
            cached_tokens = round.cached, calls = round.calls))
        dao.touch(conversationId, now, round.total ?: 0)
        recordUsage(source, round)
    }

    private suspend fun recordUsage(source: String, round: Round) {
        if (round.calls == 0) return
        dao.insertUsage(AiUsageEntity(at_ms = clock.millis(), source = source, input_tokens = round.input ?: 0,
            output_tokens = round.output ?: 0, total_tokens = round.total ?: ((round.input ?: 0) + (round.output ?: 0)),
            calls = round.calls))
    }

    override suspend fun confirmed(cardId: Long, billId: Long) = changeState(cardId, AiDraftState.CONFIRMED, billId)

    override suspend fun discard(cardId: Long) = changeState(cardId, AiDraftState.DISCARDED, null)

    override suspend fun undone(cardId: Long) {
        val card = dao.draft(cardId) ?: return
        if (card.state != AiDraftState.CONFIRMED.name) return
        // Held again before the bill goes, which would otherwise delete the pictures no one else holds.
        pins.pin(AiConversations.decodeImages(card.images).map { it.key })
        dao.setDraftState(cardId, AiDraftState.PENDING.name, null, UUID.randomUUID().toString())
    }

    private suspend fun changeState(cardId: Long, state: AiDraftState, billId: Long?) {
        val card = dao.draft(cardId) ?: return
        if (card.state != AiDraftState.PENDING.name) return
        dao.setDraftState(cardId, state.name, billId, card.operation_id)
        // A saved bill holds the pictures it kept; the rest are let go.
        pins.release(AiConversations.decodeImages(card.images).map { it.key })
    }

    override suspend fun setKeepImages(cardId: Long, keep: Boolean) = dao.setKeepImages(cardId, keep)

    override suspend fun matchBill(cardId: Long, draft: AiBillDraft, images: List<BillImage>?) {
        val card = dao.draft(cardId) ?: return
        if (card.state != AiDraftState.CONFIRMED.name) return
        // A saved card holds no pins (its bill holds its pictures), so its pictures change without pinning.
        dao.setDraftContent(cardId, AiConversations.encodeDraft(draft), images?.let(AiConversations::encodeImages) ?: card.images)
    }

    override suspend fun deleteConversation(id: Long) {
        val held = dao.pendingDraftsIn(listOf(id)).flatMap { AiConversations.decodeImages(it.images) }.map { it.key }
        dao.deleteConversation(id)
        pins.release(held)
    }

    override suspend fun clearConversations() {
        val held = dao.pendingDrafts().flatMap { AiConversations.decodeImages(it.images) }.map { it.key }
        dao.clearConversations()
        dao.clearUsage()
        pins.release(held)
    }

    override suspend fun expire() {
        val now = clock.millis()
        val old = dao.conversationsBefore(now - AiRules.KEEP_DAYS * DAY_MS)
        if (old.isNotEmpty()) {
            val held = dao.pendingDraftsIn(old).flatMap { AiConversations.decodeImages(it.images) }.map { it.key }
            old.forEach { dao.deleteConversation(it) }
            pins.release(held)
        }
        dao.deleteUsageBefore(now - USAGE_DAYS * DAY_MS)
    }

    /** Pins the pictures of every waiting draft; run when the app starts, before the library is swept. */
    suspend fun restorePins() {
        pins.pin(dao.pendingDrafts().flatMap { AiConversations.decodeImages(it.images) }.map { it.key })
    }

    /** Forgets every conversation and the usage, for clearing all data. */
    suspend fun clearAll() = withContext(Dispatchers.IO) { chatDb.clearAllTables() }

    private fun AiDraftEntity.card(): AiDraftCard? = AiConversations.decodeDraft(payload)?.let { draft ->
        AiDraftCard(id, label, draft, AiDraftState.valueOf(state), bill_id, operation_id,
            AiConversations.decodeImages(images), keep_images)
    }

    private fun message(conversationId: Long, kind: AiMessageKind, text: String, at: Long) = AiMessageEntity(
        conversation_id = conversationId, kind = kind.name, text = text, raw = null, reasoning = null, thinking_ms = null,
        input_tokens = null, output_tokens = null, total_tokens = null, cached_tokens = null, calls = 0, problem = null,
        no_drafts = false, confirmed_bills = "[]", created_at_ms = at)

    private suspend fun savedKey(): String? = withContext(Dispatchers.IO) {
        try { keys.read() } catch (_: Exception) { throw AiException(AiProblem.NO_KEY_STORAGE) }
    }

    private suspend fun context(): Context {
        val settings = service.observeSettings().first()
        val choices = db.withAccountUse(service.observeChoices().first())
        val habits = if (settings.sendHabits) db.bills().recentHabits(AiRules.HABITS).map { AiHabit(it.note, it.category_id) }
            else emptyList()
        return Context(settings, service.observeFeatures().first(), choices, db.overview().settings()?.base_currency,
            habits, LocalDateTime.now(clock).withSecond(0).withNano(0))
    }

    /** Everything one request is built from, read once so the prompt and the checks of its reply agree. */
    private class Context(val settings: AiSettings, val features: AiFeatures, val choices: AiChoices, val base: String?,
        val habits: List<AiHabit>, val now: LocalDateTime) {
        fun system(english: Boolean) = AiPrompt.system(settings, choices.categories, choices.accounts, now, base, english, habits)
        fun reader(currencyExpected: Boolean) = AiDraftReader(settings, choices.categories,
            AiPrompt.sentAccounts(choices.accounts), choices.accounts, now, base, currencyExpected)
    }

    /** Tokens and calls of one round so far. */
    private class Round {
        var calls = 0
        var input: Long? = null
        var output: Long? = null
        var total: Long? = null
        var cached: Long? = null
        var thinkingMs: Long? = null
        fun add(usage: AiUsage?) {
            if (usage == null) return
            fun plus(a: Long?, b: Long?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
            input = plus(input, usage.input); output = plus(output, usage.output)
            total = plus(total, usage.total); cached = plus(cached, usage.cached)
        }
        fun usage() = AiTurnUsage(input, output, total, cached, calls)
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        /** Usage is shown by month; a little over a year is kept. */
        const val USAGE_DAYS = 400L
    }
}
