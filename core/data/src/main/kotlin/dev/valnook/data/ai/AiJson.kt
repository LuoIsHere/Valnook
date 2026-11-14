package dev.valnook.data.ai

import dev.valnook.domain.ai.AiRules
import kotlinx.serialization.json.*

/** One tool call of a reply: its id, the tool's name and the arguments as the model wrote them. */
data class AiToolCall(val id: String, val name: String, val arguments: String)

/** Tokens a call used; null where the service did not say. */
data class AiUsage(val input: Long?, val output: Long?, val total: Long?, val cached: Long?) {
    operator fun plus(other: AiUsage) = AiUsage(add(input, other.input), add(output, other.output),
        add(total, other.total), add(cached, other.cached))
    private fun add(a: Long?, b: Long?) = if (a == null && b == null) null else (a ?: 0) + (b ?: 0)
}

/**
 * What a chat completion returned. [message] is the assistant message exactly as received, to go back into the
 * history unchanged (some services require fields such as `reasoning_content` back with tools).
 */
data class AiReply(
    val message: JsonObject,
    val content: String,
    val reasoning: String?,
    val toolCalls: List<AiToolCall>,
    val usage: AiUsage?,
)

object AiJson {
    val format = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** Empty, or a JSON object of a size a request can carry. */
    fun validParams(text: String): Boolean = runCatching { params(text) }.isSuccess

    /** The object [text] holds; empty text is no parameters. Throws for anything else. */
    fun params(text: String): JsonObject {
        if (text.isBlank()) return JsonObject(emptyMap())
        require(text.length <= AiRules.PARAMS_MAX)
        return format.parseToJsonElement(text) as? JsonObject ?: throw IllegalArgumentException("not an object")
    }

    /** [base] with [extra]'s keys laid over it; objects under the same key merge, anything else is replaced. */
    fun merge(base: JsonObject, extra: JsonObject): JsonObject = JsonObject(base.toMutableMap().apply {
        extra.forEach { (key, value) ->
            val old = this[key]
            this[key] = if (old is JsonObject && value is JsonObject) merge(old, value) else value
        }
    })

    /** Reads the first choice of a chat completion; null when the body is not one. */
    fun reply(body: String): AiReply? = runCatching {
        val root = format.parseToJsonElement(body).jsonObject
        val message = root["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject
        val raw = (message["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val (thought, content) = splitThinking(raw)
        val reasoning = listOf("reasoning_content", "reasoning").firstNotNullOfOrNull { key ->
            (message[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        } ?: thought
        val calls = (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { item ->
            val call = item as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            AiToolCall((call["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                (function["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                (function["arguments"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        }
        AiReply(message, content, reasoning, calls, usage(root["usage"] as? JsonObject))
    }.getOrNull()

    /** A leading `<think>…</think>` is the model's thinking, as some local models write it, and not part of the answer. */
    internal fun splitThinking(content: String): Pair<String?, String> {
        val text = content.trimStart()
        if (!text.startsWith("<think>")) return null to content
        val end = text.indexOf("</think>")
        if (end < 0) return text.removePrefix("<think>").trim().ifEmpty { null } to ""
        return text.substring("<think>".length, end).trim().ifEmpty { null } to text.substring(end + "</think>".length).trim()
    }

    private fun usage(usage: JsonObject?): AiUsage? {
        if (usage == null) return null
        fun long(vararg path: String): Long? {
            var node: JsonElement? = usage
            path.forEach { node = (node as? JsonObject)?.get(it) }
            return (node as? JsonPrimitive)?.longOrNull
        }
        // Services name the cached part differently; the first one present counts.
        val cached = long("prompt_cache_hit_tokens") ?: long("prompt_tokens_details", "cached_tokens") ?: long("cached_tokens")
        return AiUsage(long("prompt_tokens"), long("completion_tokens"), long("total_tokens"), cached)
    }

    /** The model ids a `/models` listing names, sorted; null when the body is not such a listing. */
    fun models(body: String): List<String>? = runCatching {
        format.parseToJsonElement(body).jsonObject["data"]!!.jsonArray
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
            .distinct().sorted()
    }.getOrNull()
}
