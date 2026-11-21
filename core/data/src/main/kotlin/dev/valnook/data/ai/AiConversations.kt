package dev.valnook.data.ai

import dev.valnook.domain.ai.AiBillDraft
import dev.valnook.domain.ai.AiDraftFlag
import dev.valnook.domain.ai.AiDraftState
import dev.valnook.domain.ai.AiMessageKind
import dev.valnook.domain.ai.AiQueryCall
import dev.valnook.domain.ai.AiQueryGroup
import dev.valnook.domain.ai.AiQueryRecord
import dev.valnook.domain.ai.AiQuerySort
import dev.valnook.domain.ai.AiRules
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillType
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64

/**
 * A tool call of one query step as stored: [result] is what later requests send for it (a summary of a query, never
 * its rows); [record] the query as run, for the page. A bill call keeps neither: its result is what became of its
 * drafts.
 */
internal data class AiStepCall(val id: String, val tool: String, val result: String? = null, val record: AiQueryRecord? = null)

/** The rules of a conversation's history and stored drafts, kept apart from storage so they can be tested alone. */
internal object AiConversations {

    /**
     * The earlier rounds sent with a new message: the last [AiRules.CONTEXT_TURNS] rounds, a round starting at each
     * message the user sent. Replies go back exactly as received, each tool call followed by its result, which says
     * what became of the drafts it made so the model knows which it may still replace. An answer's query steps go
     * before it, each query's result only a summary, so rows are not sent again. Errors are left out; pictures are not
     * sent again.
     */
    fun history(messages: List<AiMessageEntity>, drafts: List<AiDraftEntity>, pictureCounts: Map<Long, Int>,
        steps: List<AiQueryStepEntity> = emptyList()): List<JsonObject> {
        val starts = messages.withIndex().filter { it.value.kind == AiMessageKind.USER.name }.map { it.index }
        val from = starts.takeLast(AiRules.CONTEXT_TURNS).firstOrNull() ?: return emptyList()
        val byMessage = drafts.groupBy { it.message_id }
        val stepsBy = steps.groupBy { it.message_id }
        return messages.drop(from).flatMap { message ->
            when (message.kind) {
                AiMessageKind.USER.name -> listOf(AiPrompt.message("user",
                    (if (message.shared) AiPrompt.SHARE_INTRO + "\n\n" else "") +
                        userText(message.text, pictureCounts[message.id] ?: 0, earlier = true)))
                AiMessageKind.ASSISTANT.name -> {
                    val raw = parse(message.raw) ?: return@flatMap emptyList()
                    val status = status(byMessage[message.id].orEmpty())
                    var told = false
                    // The first bill call hears what became of the drafts; a query its summary.
                    fun results(reply: JsonObject, stored: Map<String, String>) = toolCalls(reply).map { (id, name) ->
                        toolResult(id, stored[id] ?: when {
                            name in AiQueryTools.names -> "Not run: the queries of this answer were used up."
                            !told -> status.also { told = true }
                            else -> "Done."
                        })
                    }
                    stepsBy[message.id].orEmpty().sortedBy { it.step }.flatMap { step ->
                        val reply = parse(step.raw) ?: return@flatMap emptyList()
                        listOf(reply) + results(reply, decodeStepCalls(step.calls)
                            .mapNotNull { call -> call.result?.let { call.id to it } }.toMap())
                    } + listOf(raw) + results(raw, emptyMap())
                }
                else -> emptyList()
            }
        }
    }

    private fun parse(raw: String?): JsonObject? =
        raw?.let { runCatching { AiJson.format.parseToJsonElement(it).jsonObject }.getOrNull() }

    /** A reply's tool calls: id and function name. */
    fun toolCalls(reply: JsonObject): List<Pair<String, String>> = (reply["tool_calls"] as? JsonArray).orEmpty().mapNotNull { call ->
        val o = call as? JsonObject ?: return@mapNotNull null
        val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        id to (((o["function"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty())
    }

    fun encodeStepCalls(calls: List<AiStepCall>): String = JsonArray(calls.map { call -> JsonObject(buildMap {
        put("id", JsonPrimitive(call.id))
        put("tool", JsonPrimitive(call.tool))
        call.result?.let { put("result", JsonPrimitive(it)) }
        call.record?.let { record ->
            put("rows", JsonPrimitive(record.rows))
            put("error", JsonPrimitive(record.error))
            record.call?.let { put("call", encodeQuery(it)) }
        }
    }) }).toString()

    fun decodeStepCalls(text: String, step: Int = 0): List<AiStepCall> = runCatching {
        AiJson.format.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            fun string(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
            val tool = string("tool").orEmpty()
            AiStepCall(string("id") ?: return@mapNotNull null, tool, string("result"),
                if (tool in AiQueryTools.names) AiQueryRecord(step, (o["call"] as? JsonObject)?.let(::decodeQuery),
                    (o["rows"] as? JsonPrimitive)?.intOrNull ?: 0, (o["error"] as? JsonPrimitive)?.booleanOrNull ?: false)
                else null)
        }
    }.getOrDefault(emptyList())

    /** The queries an answer ran, in order, for the page. */
    fun queryRecords(steps: List<AiQueryStepEntity>): List<AiQueryRecord> =
        steps.sortedBy { it.step }.flatMap { step -> decodeStepCalls(step.calls, step.step).mapNotNull { it.record } }

    fun encodeQuery(call: AiQueryCall) = JsonObject(buildMap {
        put("summary", JsonPrimitive(call.summary))
        call.type?.let { put("type", JsonPrimitive(it.name)) }
        put("from", JsonPrimitive(call.from.toString()))
        put("to", JsonPrimitive(call.to.toString()))
        put("group", JsonPrimitive(call.group.name))
        call.categoryId?.let { put("category", JsonPrimitive(it)) }
        call.accountId?.let { put("account", JsonPrimitive(it)) }
        if (call.keyword.isNotEmpty()) put("keyword", JsonPrimitive(call.keyword))
        put("sort", JsonPrimitive(call.sort.name))
        put("limit", JsonPrimitive(call.limit))
        if (call.shortened) put("shortened", JsonPrimitive(true))
        call.distribution?.let { put("distribution", JsonPrimitive(it.name)) }
    })

    fun decodeQuery(o: JsonObject): AiQueryCall? = runCatching {
        fun string(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        fun long(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
        AiQueryCall((o["summary"] as JsonPrimitive).boolean, string("type")?.let(BillType::valueOf),
            LocalDate.parse(string("from")), LocalDate.parse(string("to")),
            string("group")?.let(AiQueryGroup::valueOf) ?: AiQueryGroup.NONE, long("category"), long("account"),
            string("keyword").orEmpty(), string("sort")?.let(AiQuerySort::valueOf) ?: AiQuerySort.TIME_DESC,
            (o["limit"] as? JsonPrimitive)?.intOrNull ?: AiRules.QUERY_BILL_ROWS,
            (o["shortened"] as? JsonPrimitive)?.booleanOrNull ?: false,
            string("distribution")?.let(dev.valnook.domain.ai.AiDistributionBy::valueOf))
    }.getOrNull()

    fun toolResult(callId: String, content: String) = JsonObject(mapOf("role" to JsonPrimitive("tool"),
        "tool_call_id" to JsonPrimitive(callId), "content" to JsonPrimitive(content)))

    /** What became of a reply's drafts, for the model. */
    fun status(drafts: List<AiDraftEntity>): String {
        if (drafts.isEmpty()) return "No drafts were made."
        return "Drafts " + drafts.joinToString("; ") { draft ->
            draft.label + " " + when (draft.state) {
                AiDraftState.CONFIRMED.name -> "saved by the user; it can no longer be changed here"
                AiDraftState.DISCARDED.name -> "discarded by the user"
                AiDraftState.REPLACED.name -> "replaced by a later draft"
                else -> "waiting for the user to confirm; pass its draft_id to change it"
            }
        } + "."
    }

    /** The user's words; pictures sent in an earlier round are mentioned but not sent again. */
    fun userText(text: String, pictures: Int, earlier: Boolean): String {
        val words = text.ifBlank { if (pictures > 0) "(pictures only)" else "" }
        if (!earlier || pictures == 0) return words
        return "$words\n[$pictures picture(s) sent earlier, not repeated]"
    }

    /** A user message with [jpegs] as data URLs after the words. */
    fun userMessage(text: String, jpegs: List<ByteArray>): JsonObject {
        if (jpegs.isEmpty()) return AiPrompt.message("user", text)
        val parts = listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))) +
            jpegs.map { bytes -> JsonObject(mapOf("type" to JsonPrimitive("image_url"), "image_url" to JsonObject(mapOf(
                "url" to JsonPrimitive("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(bytes)))))) }
        return JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonArray(parts)))
    }

    /**
     * Which pictures go with which draft: one each in order when the counts match, otherwise all with the first
     * (a list of several bills in one picture, or pictures that do not line up).
     */
    fun assignPictures(pictures: List<BillImage>, drafts: Int): List<List<BillImage>> = when {
        drafts == 0 -> emptyList()
        pictures.size == drafts -> pictures.map { listOf(it) }
        else -> List(drafts) { if (it == 0) pictures else emptyList() }
    }

    fun title(text: String, pictures: Int, english: Boolean): String {
        val line = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isNotEmpty()) return if (line.codePointCount(0, line.length) <= AiRules.TITLE_LENGTH) line
            else line.substring(0, line.offsetByCodePoints(0, AiRules.TITLE_LENGTH)) + "…"
        return if (pictures > 0) (if (english) "Pictures" else "图片") else (if (english) "Conversation" else "对话")
    }

    /** The next free label: "d" and one more than the highest number used in the conversation. */
    fun nextLabel(drafts: List<AiDraftEntity>, taken: Int = 0): String =
        "d" + ((drafts.mapNotNull { it.label.removePrefix("d").toIntOrNull() }.maxOrNull() ?: 0) + 1 + taken)

    /** The reply's words: the content and the tool calls' messages, without repeats. */
    fun replyText(content: String, messages: List<String>): String =
        (listOf(content.trim()) + messages.map { it.trim() }).filter { it.isNotEmpty() }.distinct().joinToString("\n")

    fun encodeDraft(draft: AiBillDraft): String = JsonObject(buildMap {
        put("type", JsonPrimitive(draft.type.name))
        draft.amountMinor?.let { put("amount", JsonPrimitive(it)) }
        put("currency", JsonPrimitive(draft.currencyCode))
        put("discount", JsonPrimitive(draft.discountMinor))
        draft.categoryId?.let { put("category", JsonPrimitive(it)) }
        draft.subCategoryId?.let { put("sub", JsonPrimitive(it)) }
        draft.cashAccountId?.let { put("account", JsonPrimitive(it)) }
        draft.toCashAccountId?.let { put("to", JsonPrimitive(it)) }
        put("time", JsonPrimitive(draft.occurredAt.toString()))
        put("note", JsonPrimitive(draft.note))
        put("counterparty", JsonPrimitive(draft.counterparty))
        put("reimbursable", JsonPrimitive(draft.reimbursable))
        put("flags", JsonArray(draft.flags.map { JsonPrimitive(it.name) }))
        draft.toAmountMinor?.let { put("toAmount", JsonPrimitive(it)) }
        draft.rate?.let { put("rate", JsonPrimitive(it.toPlainString())) }
        draft.accountAmountMinor?.let { put("accountAmount", JsonPrimitive(it)) }
    }).toString()

    fun decodeDraft(text: String): AiBillDraft? = runCatching {
        val o = AiJson.format.parseToJsonElement(text).jsonObject
        fun long(key: String) = (o[key] as? JsonPrimitive)?.longOrNull
        fun string(key: String) = (o[key] as? JsonPrimitive)?.contentOrNull
        AiBillDraft(BillType.valueOf(string("type")!!), long("amount"), string("currency")!!, long("discount") ?: 0,
            long("category"), long("sub"), long("account"), long("to"), LocalDateTime.parse(string("time")!!),
            string("note").orEmpty(), string("counterparty").orEmpty(), (o["reimbursable"] as? JsonPrimitive)?.booleanOrNull ?: false,
            (o["flags"] as? JsonArray).orEmpty().mapNotNull { flag ->
                (flag as? JsonPrimitive)?.contentOrNull?.let { name -> AiDraftFlag.entries.firstOrNull { it.name == name } }
            }.toSet(), long("toAmount"), string("rate")?.toBigDecimalOrNull(), long("accountAmount"))
    }.getOrNull()

    fun encodeImages(images: List<BillImage>): String = JsonArray(images.map(::encodeImage)).toString()

    fun encodeImage(image: BillImage) = JsonObject(mapOf("k" to JsonPrimitive(image.key),
        "s" to JsonPrimitive(image.byteSize), "w" to JsonPrimitive(image.width), "h" to JsonPrimitive(image.height)))

    fun decodeImages(text: String): List<BillImage> = runCatching {
        AiJson.format.parseToJsonElement(text).jsonArray.mapNotNull { decodeImage(it.jsonObject) }
    }.getOrDefault(emptyList())

    fun decodeImage(o: JsonObject): BillImage? = runCatching {
        BillImage(o["k"]!!.jsonPrimitive.content, o["s"]!!.jsonPrimitive.long, o["w"]!!.jsonPrimitive.int, o["h"]!!.jsonPrimitive.int)
    }.getOrNull()

    fun encodeIds(ids: List<Long>): String = JsonArray(ids.map(::JsonPrimitive)).toString()

    fun decodeIds(text: String): List<Long> = runCatching {
        AiJson.format.parseToJsonElement(text).jsonArray.mapNotNull { it.jsonPrimitive.longOrNull }
    }.getOrDefault(emptyList())
}
