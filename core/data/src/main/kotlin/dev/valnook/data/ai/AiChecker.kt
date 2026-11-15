package dev.valnook.data.ai

import dev.valnook.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.util.Base64
import kotlin.random.Random

/**
 * Real requests that check what the model can do, judging the answers rather than trusting a listing: the connection,
 * a full tool round trip, reading digits from a picture, and deep thinking. The first two are required.
 */
class AiChecker(
    private val client: AiClient,
    private val drawCode: (String) -> ByteArray,
    private val random: Random = Random.Default,
) {
    suspend fun run(connection: AiConnection, apiKey: String?, progress: (AiCheckItem) -> Unit): Map<AiCheckItem, AiCheck> {
        val results = linkedMapOf<AiCheckItem, AiCheck>()
        progress(AiCheckItem.CONNECT)
        results[AiCheckItem.CONNECT] = attempt(required = true) { connect(connection, apiKey) }
        if (results.getValue(AiCheckItem.CONNECT).state != AiCheckState.PASSED) return results
        progress(AiCheckItem.TOOLS)
        results[AiCheckItem.TOOLS] = attempt { tools(connection, apiKey) }
        progress(AiCheckItem.IMAGE)
        results[AiCheckItem.IMAGE] = attempt { image(connection, apiKey) }
        if (connection.thinkingOnParams.isBlank()) results[AiCheckItem.THINKING] = AiCheck(AiCheckState.NOT_CONFIGURED)
        else {
            progress(AiCheckItem.THINKING)
            results[AiCheckItem.THINKING] = attempt { thinking(connection, apiKey) }
        }
        return results
    }

    /** A short message must come back with something in it. */
    private suspend fun connect(connection: AiConnection, apiKey: String?): AiCheck {
        val reply = client.chat(connection, apiKey, body(listOf(user("Reply with the word OK.")), 64), false, AiRules.TIMEOUT_TEXT_MS)
        return if (reply.content.isNotBlank() || !reply.reasoning.isNullOrBlank()) AiCheck(AiCheckState.PASSED)
            else AiCheck(AiCheckState.FAILED, AiProblem.BAD_RESPONSE)
    }

    /** The model must call get_code, then read the code it returned back to the user. */
    private suspend fun tools(connection: AiConnection, apiKey: String?): AiCheck {
        val code = code()
        val tool = JsonObject(mapOf("type" to JsonPrimitive("function"), "function" to JsonObject(mapOf(
            "name" to JsonPrimitive("get_code"), "description" to JsonPrimitive("Returns the verification code."),
            // An empty required list, as stricter services reject a schema without one.
            "parameters" to JsonObject(mapOf("type" to JsonPrimitive("object"), "properties" to JsonObject(emptyMap()),
                "required" to JsonArray(emptyList())))))))
        val ask = user("Call the get_code tool to get the verification code, then tell me the code.")
        val first = client.chat(connection, apiKey, body(listOf(ask), ANSWER_TOKENS, listOf(tool)), false, AiRules.TIMEOUT_TEXT_MS)
        val calls = first.toolCalls.filter { it.name == "get_code" }
        if (calls.isEmpty()) return AiCheck(AiCheckState.FAILED, AiProblem.NO_TOOL_CALL)
        // Every call gets its result, as the protocol requires; the assistant message goes back as it came.
        val history = listOf(ask, first.message) + first.toolCalls.map { call ->
            JsonObject(mapOf("role" to JsonPrimitive("tool"), "tool_call_id" to JsonPrimitive(call.id),
                "content" to JsonPrimitive(if (call.name == "get_code") code else "Unknown tool")))
        }
        val second = client.chat(connection, apiKey, body(history, ANSWER_TOKENS, listOf(tool)), false, AiRules.TIMEOUT_TEXT_MS)
        return if (code in second.content) AiCheck(AiCheckState.PASSED) else AiCheck(AiCheckState.FAILED, AiProblem.WRONG_ANSWER)
    }

    /** A picture of four random digits drawn here; the answer must hold them. */
    private suspend fun image(connection: AiConnection, apiKey: String?): AiCheck {
        val code = code()
        val url = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(drawCode(code))
        val message = JsonObject(mapOf("role" to JsonPrimitive("user"), "content" to JsonArray(listOf(
            JsonObject(mapOf("type" to JsonPrimitive("text"),
                "text" to JsonPrimitive("What is the number in this picture? Reply with the digits only."))),
            JsonObject(mapOf("type" to JsonPrimitive("image_url"), "image_url" to JsonObject(mapOf("url" to JsonPrimitive(url)))))))))
        val reply = client.chat(connection, apiKey, body(listOf(message), ANSWER_TOKENS), false, AiRules.TIMEOUT_IMAGE_MS)
        return if (code in reply.content.filter(Char::isDigit)) AiCheck(AiCheckState.PASSED)
            else AiCheck(AiCheckState.FAILED, AiProblem.WRONG_ANSWER)
    }

    /** With the thinking parameters on, the reply must carry its thinking. */
    private suspend fun thinking(connection: AiConnection, apiKey: String?): AiCheck {
        val reply = client.chat(connection, apiKey, body(listOf(user("What is 17 + 25? Answer with the number only.")), AiRules.MAX_TOKENS_THINKING),
            true, AiRules.TIMEOUT_THINKING_MS)
        return if (!reply.reasoning.isNullOrBlank()) AiCheck(AiCheckState.PASSED) else AiCheck(AiCheckState.FAILED, AiProblem.NO_THINKING)
    }

    /**
     * Network trouble and a busy or unpaid service leave a capability undecided; any other refusal means the model
     * cannot do it. For the connection itself a wrong key or address is a failure, with its reason.
     */
    private suspend fun attempt(required: Boolean = false, block: suspend () -> AiCheck): AiCheck = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: AiException) {
            val undecided = if (required) error.problem in setOf(AiProblem.OFFLINE, AiProblem.TIMEOUT, AiProblem.RATE_LIMITED, AiProblem.SERVER)
                else error.problem.transient
            AiCheck(if (undecided) AiCheckState.UNKNOWN else AiCheckState.FAILED, error.problem)
        }
        catch (_: IllegalArgumentException) { AiCheck(AiCheckState.FAILED, AiProblem.REJECTED) }

    private companion object {
        /**
         * Room for an answer after thinking the service was asked not to do: some relays think anyway (seen on
         * pictures), and a tight limit leaves an empty answer. Only what is written is billed.
         */
        const val ANSWER_TOKENS = AiRules.MAX_TOKENS
    }

    private fun code() = (1..AiRules.CODE_DIGITS).joinToString("") { random.nextInt(10).toString() }
        .let { if (it.first() == '0') "1" + it.drop(1) else it }

    private fun user(text: String) = AiPrompt.message("user", text)

    private fun body(messages: List<JsonObject>, maxTokens: Int, tools: List<JsonObject>? = null) = JsonObject(buildMap {
        put("messages", JsonArray(messages))
        tools?.let { put("tools", JsonArray(it)) }
        put("max_tokens", JsonPrimitive(maxTokens))
    })
}
