package dev.valnook.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillImageRules
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillImageLibrary
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

enum class AiSharePhase {
    /** Reading the shared pictures into the library. */
    PREPARING,
    /** No AI for this share: the bill page opens with it. */
    MANUAL,
    READING,
    RESULT,
    FAILED,
}

data class AiShareUi(
    val phase: AiSharePhase = AiSharePhase.PREPARING,
    val text: String = "",
    val pictures: List<BillImage> = emptyList(),
    /** Shared pictures that could not be read. */
    val picturesFailed: Int = 0,
    val host: String = "",
    val message: String = "",
    val cards: List<AiDraftCard> = emptyList(),
    val usage: AiTurnUsage? = null,
    val problem: AiProblem? = null,
    val busyCards: Set<Long> = emptySet(),
    val money: Map<Long, AiDraftMoney> = emptyMap(),
    val saveFailed: Boolean = false,
    val saved: Int = 0,
    val categories: Map<Long, BillCategory> = emptyMap(),
    val accounts: Map<Long, BillAccountChoice> = emptyMap(),
) {
    /** Every card was dealt with: the page can close. */
    val finished: Boolean get() = phase == AiSharePhase.RESULT && cards.isNotEmpty() && cards.none { it.state == AiDraftState.PENDING }
}

/**
 * The share page: text or up to four pictures from another app, read once into drafts when AI is on for them
 * ([useAi]), otherwise handed to the bill page. Nothing of it enters the conversations; its tokens count in the usage.
 */
class AiShareViewModel(
    private val assistant: AiAssistant?,
    private val useAi: Boolean,
    host: String,
    text: String,
    sources: List<suspend () -> ByteArray>,
    bills: BillRepository,
    private val saver: AiDraftSaver,
    private val images: BillImageLibrary?,
    private val clock: Clock,
) : ViewModel() {
    private val mutable = MutableStateFlow(AiShareUi(text = text.trim(), host = host))
    val state: StateFlow<AiShareUi> = mutable
    private var job: Job? = null

    init {
        viewModelScope.launch {
            combine(bills.observeCategories(), bills.observeAccountChoices()) { c, a -> c to a }.collect { (c, a) ->
                mutable.update { it.copy(categories = c.associateBy { x -> x.id }, accounts = a.associateBy { x -> x.cashAccountId }) }
            }
        }
        viewModelScope.launch {
            val stored = mutableListOf<BillImage>()
            var failed = 0
            sources.take(AiRules.MAX_IMAGES).forEach { source ->
                try {
                    val image = images?.store(source()) ?: throw IllegalStateException()
                    if (stored.none { it.key == image.key }) stored += image
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { failed++ }
            }
            mutable.update { it.copy(pictures = stored, picturesFailed = failed) }
            if (useAi && assistant != null && (stored.isNotEmpty() || mutable.value.text.isNotEmpty())) read()
            else mutable.update { it.copy(phase = AiSharePhase.MANUAL) }
        }
    }

    fun read() {
        val chat = assistant ?: return
        val current = mutable.value
        mutable.update { it.copy(phase = AiSharePhase.READING, problem = null) }
        job = viewModelScope.launch {
            try {
                val result = chat.recognize(current.text, current.pictures)
                val pictures = assign(current.pictures, result.drafts.size)
                val cards = result.drafts.mapIndexed { index, draft ->
                    AiDraftCard(index + 1L, "d${index + 1}", draft, AiDraftState.PENDING, null, UUID.randomUUID().toString(),
                        pictures.getOrElse(index) { emptyList() }, keepImages = true)
                }
                mutable.update { it.copy(phase = AiSharePhase.RESULT, message = result.message, cards = cards, usage = result.usage) }
                val found = cards.mapNotNull { card -> saver.money(card.draft)?.let { card.id to it } }.toMap()
                mutable.update { it.copy(money = found) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: AiException) { mutable.update { it.copy(phase = AiSharePhase.FAILED, problem = error.problem) } }
            catch (_: Exception) { mutable.update { it.copy(phase = AiSharePhase.FAILED, problem = AiProblem.BAD_RESPONSE) } }
        }
    }

    /** Stops reading; the share goes to the bill page instead. */
    fun cancel() {
        job?.cancel()
        mutable.update { it.copy(phase = AiSharePhase.MANUAL) }
    }

    fun confirm(card: AiDraftCard, onOpen: (prefill: String, operation: String) -> Unit) = busy(card) {
        when (val outcome = saver.save(card.draft, if (card.keepImages) card.images else emptyList(), card.operationId)) {
            is AiSaveOutcome.Saved -> setCard(card.id) { it.copy(state = AiDraftState.CONFIRMED, billId = outcome.billId) }
            is AiSaveOutcome.Open -> onOpen(outcome.prefill, card.operationId)
            is AiSaveOutcome.Failed -> mutable.update { it.copy(saveFailed = true) }
        }
    }

    fun confirmAll() {
        val waiting = mutable.value.cards.filter { it.state == AiDraftState.PENDING && it.draft.hasAmount &&
            mutable.value.money[it.id]?.missingRate != true }
        viewModelScope.launch {
            waiting.forEach { card ->
                val outcome = saver.save(card.draft, if (card.keepImages) card.images else emptyList(), card.operationId)
                if (outcome is AiSaveOutcome.Saved) setCard(card.id) { it.copy(state = AiDraftState.CONFIRMED, billId = outcome.billId) }
                else if (outcome is AiSaveOutcome.Failed) mutable.update { it.copy(saveFailed = true) }
            }
        }
    }

    fun edit(card: AiDraftCard, onOpen: (prefill: String, operation: String) -> Unit) = busy(card) {
        onOpen(saver.prefill(card.draft, if (card.keepImages) card.images else emptyList()), card.operationId)
    }

    fun discard(card: AiDraftCard) = setCard(card.id) { it.copy(state = AiDraftState.DISCARDED) }

    fun undo(card: AiDraftCard) {
        val billId = card.billId ?: return
        busy(card) {
            if (saver.delete(billId)) setCard(card.id) { it.copy(state = AiDraftState.PENDING, billId = null,
                operationId = UUID.randomUUID().toString()) }
            else mutable.update { it.copy(saveFailed = true) }
        }
    }

    fun setKeepImages(card: AiDraftCard, keep: Boolean) = setCard(card.id) { it.copy(keepImages = keep) }

    /** Back from the bill page: drafts saved there become saved cards. */
    fun refresh() {
        viewModelScope.launch {
            mutable.value.cards.filter { it.state == AiDraftState.PENDING }.forEach { card ->
                saver.savedAs(card.operationId)?.let { id -> setCard(card.id) { it.copy(state = AiDraftState.CONFIRMED, billId = id) } }
            }
        }
    }

    /** The bill page's form for the share as it came: the words in the note, the pictures on the bill. */
    suspend fun manualPrefill(): String {
        val current = mutable.value
        val draft = AiBillDraft(BillType.EXPENSE, null, "", occurredAt = LocalDateTime.now(clock).withSecond(0).withNano(0),
            note = current.text)
        return saver.prefill(draft, current.pictures.take(BillImageRules.MAX_PER_BILL))
    }

    fun dismissSaveFailed() = mutable.update { it.copy(saveFailed = false) }

    private fun setCard(id: Long, change: (AiDraftCard) -> AiDraftCard) = mutable.update { ui ->
        val cards = ui.cards.map { if (it.id == id) change(it) else it }
        val saved = cards.count { it.state == AiDraftState.CONFIRMED }
        ui.copy(cards = cards, saved = saved)
    }

    private fun busy(card: AiDraftCard, block: suspend () -> Unit) {
        if (card.id in mutable.value.busyCards) return
        viewModelScope.launch {
            mutable.update { it.copy(busyCards = it.busyCards + card.id) }
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(saveFailed = true) } }
            finally { mutable.update { it.copy(busyCards = it.busyCards - card.id) } }
        }
    }

    override fun onCleared() {
        // Pictures no saved bill holds go; those on bills stay with them.
        images?.drop(mutable.value.pictures.map { it.key })
    }

    companion object {
        /** One picture per draft when the counts match, otherwise all with the first: as in a conversation. */
        fun assign(pictures: List<BillImage>, drafts: Int): List<List<BillImage>> = when {
            drafts == 0 -> emptyList()
            pictures.size == drafts -> pictures.map { listOf(it) }
            else -> List(drafts) { if (it == 0) pictures else emptyList() }
        }
    }
}
