package dev.valnook.domain.ai

import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillType
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime

/** What the checks of a draft changed or filled in, shown on its card. */
enum class AiDraftFlag {
    /** The model's category was missing or not on the list: the default one is used. */
    CATEGORY,
    /** The model's account was missing or not on the list: filled from the category's last account or the default. */
    ACCOUNT,
    /** The time could not be read or was more than a year away: now is used. */
    TIME,
    /** No usable amount: the draft needs one before it can be saved. */
    AMOUNT,
}

/**
 * A bill the model proposed, checked against the lists it was sent: every id in it exists. A transfer with no
 * [cashAccountId] comes from outside, one with no [toCashAccountId] goes outside. [amountMinor] is in
 * [currencyCode]; a transfer between currencies said by what arrived has [toAmountMinor] (in the receiving account's
 * currency) instead, and the app works out what left.
 */
data class AiBillDraft(
    val type: BillType,
    val amountMinor: Long?,
    val currencyCode: String,
    val discountMinor: Long = 0,
    val categoryId: Long? = null,
    val subCategoryId: Long? = null,
    val cashAccountId: Long? = null,
    val toCashAccountId: Long? = null,
    val occurredAt: LocalDateTime,
    val note: String = "",
    val counterparty: String = "",
    val reimbursable: Boolean = false,
    val flags: Set<AiDraftFlag> = emptySet(),
    val toAmountMinor: Long? = null,
) {
    /** An amount was given, paid or (for a transfer between currencies) received. */
    val hasAmount: Boolean get() = amountMinor != null || toAmountMinor != null
}

enum class AiDraftState {
    PENDING,
    CONFIRMED,
    DISCARDED,
    /** A later reply changed it: the new card carries the same label. */
    REPLACED,
}

/**
 * A draft card in a conversation. [label] is how the model names it ("d1"); [operationId] saves it, so a save whose
 * outcome was unknown, or one made from the bill form, can be matched up later. [images] go on the bill when
 * [keepImages] is on.
 */
data class AiDraftCard(
    val id: Long,
    val label: String,
    val draft: AiBillDraft,
    val state: AiDraftState,
    val billId: Long?,
    val operationId: String,
    val images: List<BillImage>,
    val keepImages: Boolean,
)

/** Tokens of one round: everything from a message sent to the reply shown. Null parts the service did not report. */
data class AiTurnUsage(val input: Long?, val output: Long?, val total: Long?, val cached: Long?, val calls: Int) {
    val known: Boolean get() = total != null || input != null || output != null
}

enum class AiMessageKind { USER, ASSISTANT, ERROR }

/** A picture on a message the user sent: a small copy kept with the conversation. */
data class AiThumbnail(val bytes: ByteArray) {
    override fun equals(other: Any?) = other is AiThumbnail && other.bytes.contentEquals(bytes)
    override fun hashCode() = bytes.contentHashCode()
}

/**
 * One message of a conversation. An assistant message may hold drafts, and [confirmedBills] names bills a reply tried
 * to change after they were saved. An error message has a [problem], or none when the user cancelled.
 */
data class AiChatMessage(
    val id: Long,
    val kind: AiMessageKind,
    val text: String,
    val pictures: List<AiThumbnail> = emptyList(),
    val reasoning: String? = null,
    val thinkingMs: Long? = null,
    val usage: AiTurnUsage? = null,
    val problem: AiProblem? = null,
    /** The model answered without making drafts. */
    val noDrafts: Boolean = false,
    val drafts: List<AiDraftCard> = emptyList(),
    val confirmedBills: List<Long> = emptyList(),
    val createdAtMs: Long,
)

data class AiConversation(val id: Long, val title: String, val createdAtMs: Long, val lastAtMs: Long, val tokens: Long)

/** This month's requests and tokens, chat and shares together. */
data class AiUsageSummary(val requests: Int = 0, val tokens: Long = 0, val input: Long = 0, val output: Long = 0)

/** What a shared text or picture was read as. [toolCalled] is false when the model did not answer with drafts. */
data class AiRecognition(
    val message: String,
    val drafts: List<AiBillDraft>,
    val usage: AiTurnUsage?,
    val toolCalled: Boolean,
)

/** Bookkeeping by conversation and by sharing. Real data only; nothing here saves a bill. */
interface AiAssistant {
    fun observeConversations(): Flow<List<AiConversation>>
    fun observeMessages(conversationId: Long): Flow<List<AiChatMessage>>
    /** Whether any conversation has drafts waiting. */
    fun observePendingDrafts(): Flow<Boolean>
    fun observeUsage(): Flow<AiUsageSummary>
    /** The latest conversation when its last message is recent enough to go on with; null starts a new one. */
    suspend fun conversationToContinue(): Long?
    /**
     * Sends [text] and [pictures] (pictures already in the bill picture library) in [conversationId], or in a new
     * conversation when null, and stores the reply or the error. Returns the conversation's id.
     */
    suspend fun send(conversationId: Long?, text: String, pictures: List<BillImage>): Long
    /** Sends the last message of [conversationId] again after an error. */
    suspend fun retry(conversationId: Long)
    /** Reads a shared text or pictures once: no conversation, no thinking, the draft tool required. */
    suspend fun recognize(text: String, pictures: List<BillImage>): AiRecognition
    suspend fun confirmed(cardId: Long, billId: Long)
    suspend fun discard(cardId: Long)
    /** The saved bill was deleted again: the card waits once more. */
    suspend fun undone(cardId: Long)
    suspend fun setKeepImages(cardId: Long, keep: Boolean)
    suspend fun deleteConversation(id: Long)
    suspend fun clearConversations()
    /** Removes conversations past keeping; also run when the app starts. */
    suspend fun expire()
}
