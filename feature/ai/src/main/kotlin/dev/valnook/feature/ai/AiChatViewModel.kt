package dev.valnook.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillImageRules
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillImageLibrary
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

data class AiChatUi(
    val loaded: Boolean = false,
    val conversationId: Long? = null,
    val title: String? = null,
    val messages: List<AiChatMessage> = emptyList(),
    val sending: Boolean = false,
    val input: String = "",
    /** Pictures picked for the next message, already in the picture library. */
    val pictures: List<BillImage> = emptyList(),
    val picturesWorking: Int = 0,
    val pictureFailed: Boolean = false,
    val features: AiFeatures = AiFeatures(),
    val host: String = "",
    val categories: Map<Long, BillCategory> = emptyMap(),
    val accounts: Map<Long, BillAccountChoice> = emptyMap(),
    val conversations: List<AiConversation> = emptyList(),
    /** Cards being saved or taken back. */
    val busyCards: Set<Long> = emptySet(),
    val cardError: ErrorCode? = null,
    val cardFailed: Boolean = false,
    val userNickname: String = "",
    val aiNickname: String = "",
    /** Waiting drafts across currencies, by card. */
    val money: Map<Long, AiDraftMoney> = emptyMap(),
) {
    /** The last message is the user's, waiting for a reply or for a retry after an error. */
    val canRetry: Boolean get() = !sending && messages.lastOrNull()?.kind == AiMessageKind.ERROR
}

/**
 * The AI chat page: one conversation at a time. Opening the page goes on with the latest conversation unless it has
 * been quiet for an hour; drafts are saved here or opened in the bill page.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatViewModel(
    private val assistant: AiAssistant,
    private val service: AiService,
    bills: BillRepository,
    private val saver: AiDraftSaver,
    private val images: BillImageLibrary?,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val conversation = MutableStateFlow(saved.get<Long>("conversation"))
    private val local = MutableStateFlow(Local(input = saved.get<String>("input").orEmpty(),
        pictures = saved.get<String>("pictures")?.let(::decodePictures).orEmpty()))
    private var sendJob: Job? = null

    private data class Local(val loaded: Boolean = false, val sending: Boolean = false, val input: String = "",
        val pictures: List<BillImage> = emptyList(), val picturesWorking: Int = 0, val pictureFailed: Boolean = false,
        val busyCards: Set<Long> = emptySet(), val cardError: ErrorCode? = null, val cardFailed: Boolean = false,
        val money: Map<Long, AiDraftMoney> = emptyMap())

    private val messages = conversation.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else assistant.observeMessages(id) }
    private val choices = combine(bills.observeCategories(), bills.observeAccountChoices()) { c, a ->
        c.associateBy { it.id } to a.associateBy { it.cashAccountId }
    }

    val state: StateFlow<AiChatUi> = combine(
        combine(conversation, local, messages) { id, l, m -> Triple(id, l, m) },
        service.observeFeatures(), service.observeSettings(), choices, assistant.observeConversations(),
    ) { (id, l, m), features, settings, (categories, accounts), conversations ->
        AiChatUi(l.loaded, id, conversations.firstOrNull { it.id == id }?.title, m, l.sending, l.input, l.pictures,
            l.picturesWorking, l.pictureFailed, features, settings.connection.host, categories, accounts, conversations,
            l.busyCards, l.cardError, l.cardFailed, settings.userNickname, settings.aiNickname, l.money)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, AiChatUi())

    init {
        // Pictures picked before the app was ended are held again until sent or dropped.
        images?.hold(local.value.pictures.map { it.key })
        viewModelScope.launch {
            if (!saved.contains("conversation")) {
                runCatching { assistant.expire() }
                setConversation(runCatching { assistant.conversationToContinue() }.getOrNull())
            }
            local.update { it.copy(loaded = true) }
        }
        // New waiting drafts get their conversion worked out once.
        viewModelScope.launch {
            messages.collect { list ->
                val waiting = list.flatMap { it.drafts }.filter { it.state == AiDraftState.PENDING && it.id !in local.value.money }
                if (waiting.isNotEmpty()) workOutMoney(waiting)
            }
        }
    }

    private suspend fun workOutMoney(cards: List<AiDraftCard>) {
        val found = cards.mapNotNull { card -> saver.money(card.draft)?.let { card.id to it } }.toMap()
        local.update { it.copy(money = it.money - cards.map(AiDraftCard::id).toSet() + found) }
    }

    private fun setConversation(id: Long?) {
        conversation.value = id
        saved["conversation"] = id
    }

    private fun updateLocal(change: (Local) -> Local) {
        local.update(change)
        saved["input"] = local.value.input
        saved["pictures"] = encodePictures(local.value.pictures)
    }

    fun setInput(text: String) = updateLocal { it.copy(input = text.take(INPUT_MAX)) }

    /** Reads, compresses and stores picked pictures, up to the limit of one message. */
    fun addPictures(sources: List<suspend () -> ByteArray>) {
        val library = images ?: return
        val room = AiRules.MAX_IMAGES - local.value.pictures.size - local.value.picturesWorking
        val picked = sources.take(room.coerceAtLeast(0))
        if (picked.isEmpty()) return
        updateLocal { it.copy(picturesWorking = it.picturesWorking + picked.size, pictureFailed = false) }
        viewModelScope.launch {
            picked.forEach { source ->
                try {
                    val image = library.store(source())
                    if (local.value.pictures.any { it.key == image.key } || local.value.pictures.size >= AiRules.MAX_IMAGES)
                        library.saved(listOf(image.key))
                    else updateLocal { it.copy(pictures = it.pictures + image) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { updateLocal { it.copy(pictureFailed = true) } }
                finally { updateLocal { it.copy(picturesWorking = it.picturesWorking - 1) } }
            }
        }
    }

    fun removePicture(key: String) {
        if (local.value.pictures.none { it.key == key }) return
        updateLocal { it.copy(pictures = it.pictures.filter { p -> p.key != key }) }
        images?.drop(listOf(key))
    }

    fun send() {
        val current = local.value
        val text = current.input.trim()
        if (current.sending || current.picturesWorking > 0 || (text.isEmpty() && current.pictures.isEmpty())) return
        if (!state.value.features.chatEntry) return
        val pictures = current.pictures
        updateLocal { it.copy(sending = true, input = "", pictures = emptyList(), cardError = null, cardFailed = false) }
        sendJob = viewModelScope.launch {
            try { setConversation(assistant.send(conversation.value, text, pictures)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                // Nothing was stored (the features went off meanwhile): the words come back to the box.
                updateLocal { it.copy(input = text) }
            } finally {
                // Waiting drafts hold their own pictures now; the rest go once nothing holds them.
                images?.saved(pictures.map { it.key })
                updateLocal { it.copy(sending = false) }
            }
        }
    }

    fun cancel() { sendJob?.cancel() }

    fun retry() {
        val id = conversation.value ?: return
        if (local.value.sending) return
        updateLocal { it.copy(sending = true) }
        sendJob = viewModelScope.launch {
            try { assistant.retry(id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
            finally { updateLocal { it.copy(sending = false) } }
        }
    }

    fun newConversation() {
        if (local.value.sending) return
        setConversation(null)
    }

    fun openConversation(id: Long) {
        if (local.value.sending) return
        setConversation(id)
    }

    fun deleteConversation(id: Long) {
        viewModelScope.launch {
            runCatching { assistant.deleteConversation(id) }
            if (conversation.value == id) setConversation(null)
        }
    }

    /** Saves [card]; [onOpen] gets the bill page's form when it cannot be saved as it is. */
    fun confirm(card: AiDraftCard, onOpen: (prefill: String, operation: String) -> Unit) {
        if (card.state != AiDraftState.PENDING || card.id in local.value.busyCards) return
        busy(card.id) {
            when (val outcome = saver.save(card.draft, if (card.keepImages) card.images else emptyList(), card.operationId)) {
                is AiSaveOutcome.Saved -> assistant.confirmed(card.id, outcome.billId)
                is AiSaveOutcome.Open -> onOpen(outcome.prefill, card.operationId)
                is AiSaveOutcome.Failed -> updateLocal { it.copy(cardError = outcome.code, cardFailed = true) }
            }
        }
    }

    /** Saves every waiting card of a reply that can be saved as it is; the rest stay. */
    fun confirmAll(cards: List<AiDraftCard>) {
        val waiting = cards.filter { it.state == AiDraftState.PENDING && it.id !in local.value.busyCards && it.draft.hasAmount &&
            local.value.money[it.id]?.missingRate != true }
        if (waiting.isEmpty()) return
        viewModelScope.launch {
            waiting.forEach { card ->
                setBusy(card.id, true)
                try {
                    val outcome = saver.save(card.draft, if (card.keepImages) card.images else emptyList(), card.operationId)
                    if (outcome is AiSaveOutcome.Saved) assistant.confirmed(card.id, outcome.billId)
                    else if (outcome is AiSaveOutcome.Failed) updateLocal { it.copy(cardError = outcome.code, cardFailed = true) }
                } finally { setBusy(card.id, false) }
            }
        }
    }

    /** The bill page's form for [card], saved under the card's operation so the page's save is found afterwards. */
    fun edit(card: AiDraftCard, onOpen: (prefill: String, operation: String) -> Unit) {
        if (card.state != AiDraftState.PENDING) return
        busy(card.id) { onOpen(saver.prefill(card.draft, if (card.keepImages) card.images else emptyList()), card.operationId) }
    }

    fun discard(card: AiDraftCard) = busy(card.id) { assistant.discard(card.id) }

    /** Deletes the saved bill again; the card waits once more. */
    fun undo(card: AiDraftCard) {
        val billId = card.billId ?: return
        busy(card.id) {
            assistant.undone(card.id)
            if (!saver.delete(billId)) {
                assistant.confirmed(card.id, billId)
                updateLocal { it.copy(cardFailed = true) }
            }
        }
    }

    fun setKeepImages(card: AiDraftCard, keep: Boolean) {
        viewModelScope.launch { runCatching { assistant.setKeepImages(card.id, keep) } }
    }

    fun dismissCardError() = updateLocal { it.copy(cardError = null, cardFailed = false) }

    /** Words and pictures for the bill page when the model was no help: the words go in the note. */
    fun manual(text: String, pictures: List<BillImage>, onOpen: (prefill: String) -> Unit) {
        viewModelScope.launch {
            val draft = AiBillDraft(dev.valnook.domain.model.BillType.EXPENSE, null, "", occurredAt =
                java.time.LocalDateTime.now().withSecond(0).withNano(0), note = text.trim())
            onOpen(saver.prefill(draft, pictures.take(BillImageRules.MAX_PER_BILL)))
        }
    }

    /**
     * Back on the page: drafts saved from the bill page meanwhile become saved cards, and conversions are worked out
     * again (a rate may have been set in the meantime).
     */
    fun refresh() {
        viewModelScope.launch {
            val waiting = state.value.messages.flatMap { it.drafts }.filter { it.state == AiDraftState.PENDING }
            waiting.forEach { card ->
                saver.savedAs(card.operationId)?.let { runCatching { assistant.confirmed(card.id, it) } }
            }
            workOutMoney(waiting)
        }
    }

    private fun busy(cardId: Long, block: suspend () -> Unit) {
        if (cardId in local.value.busyCards) return
        viewModelScope.launch {
            setBusy(cardId, true)
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { updateLocal { it.copy(cardFailed = true) } }
            finally { setBusy(cardId, false) }
        }
    }

    private fun setBusy(cardId: Long, busy: Boolean) =
        updateLocal { it.copy(busyCards = if (busy) it.busyCards + cardId else it.busyCards - cardId) }

    override fun onCleared() {
        // Pictures picked but never sent go, unless something else holds them.
        if (!local.value.sending) images?.drop(local.value.pictures.map { it.key })
    }

    private companion object {
        const val INPUT_MAX = 2000

        fun encodePictures(pictures: List<BillImage>): String = JsonArray(pictures.map {
            JsonObject(mapOf("k" to JsonPrimitive(it.key), "s" to JsonPrimitive(it.byteSize),
                "w" to JsonPrimitive(it.width), "h" to JsonPrimitive(it.height)))
        }).toString()

        fun decodePictures(text: String): List<BillImage> = runCatching {
            Json.parseToJsonElement(text).jsonArray.map { item ->
                val o = item.jsonObject
                BillImage(o["k"]!!.jsonPrimitive.content, o["s"]!!.jsonPrimitive.long, o["w"]!!.jsonPrimitive.int, o["h"]!!.jsonPrimitive.int)
            }
        }.getOrDefault(emptyList())
    }
}
