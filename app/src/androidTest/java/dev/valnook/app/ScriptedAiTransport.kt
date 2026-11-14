package dev.valnook.app

import dev.valnook.data.ai.AiHttpRequest
import dev.valnook.data.ai.AiHttpResponse
import dev.valnook.data.ai.AiTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The AI service the UI tests talk to: replies are queued by each test; nothing reaches the network. */
object ScriptedAiTransport : AiTransport {
    private val replies = ArrayDeque<suspend (JsonObject?) -> AiHttpResponse>()
    private val sent = mutableListOf<JsonObject?>()

    val requests: List<JsonObject?> get() = synchronized(this) { sent.toList() }

    fun reset() = synchronized(this) { replies.clear(); sent.clear() }

    fun reply(answer: suspend (JsonObject?) -> AiHttpResponse) = synchronized(this) { replies.addLast(answer) }

    override suspend fun execute(request: AiHttpRequest): AiHttpResponse {
        val body = request.body?.let { Json.parseToJsonElement(String(it)).jsonObject }
        val next = synchronized(this) { sent += body; replies.removeFirstOrNull() }
        return next?.invoke(body) ?: AiHttpResponse(503, "")
    }
}
