package dev.valnook.data.ai

import dev.valnook.domain.ai.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** The four checks against a scripted service that answers like a capable model unless told otherwise. */
class AiCheckerTest {
    private val connection = AiConnection(null, "https://api.example.test", "model-a",
        thinkingOffParams = """{"thinking": {"type": "disabled"}}""", thinkingOnParams = """{"thinking": {"type": "enabled"}}""")
    private var drawn = ""
    private val requests = mutableListOf<JsonObject>()

    /** What the scripted model does at each step; null answers like a capable model. */
    private var onConnect: (() -> AiHttpResponse)? = null
    private var onTools: (() -> AiHttpResponse)? = null
    private var onImage: (() -> AiHttpResponse)? = null
    private var onThinking: (() -> AiHttpResponse)? = null

    private fun ok(content: String, extra: String = "") =
        AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":${JsonPrimitive(content)}$extra}}]}""")

    private val transport = AiTransport { request ->
        val body = AiJson.format.parseToJsonElement(String(request.body!!)).jsonObject
        requests += body
        val messages = body["messages"]!!.jsonArray
        val last = messages.last().jsonObject
        val thinkingOn = body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "enabled"
        when {
            thinkingOn -> onThinking?.invoke() ?: ok("42", ",\"reasoning_content\":\"17 plus 25\"")
            last["content"] is JsonArray -> onImage?.invoke() ?: ok("The number is $drawn.")
            last["role"]!!.jsonPrimitive.content == "tool" -> ok("The code is ${last["content"]!!.jsonPrimitive.content}.")
            body["tools"] != null -> onTools?.invoke() ?: ok("", ""","reasoning_content":"call it","tool_calls":[{"id":"t1",
                "type":"function","function":{"name":"get_code","arguments":"{}"}}]""")
            else -> onConnect?.invoke() ?: ok("OK")
        }
    }
    private val checker = AiChecker(AiClient(transport), { code -> drawn = code; byteArrayOf(1, 2, 3) }, kotlin.random.Random(7))

    private suspend fun run(connection: AiConnection = this.connection) = checker.run(connection, "k") {}

    @Test fun a_capable_model_passes_everything() = runTest {
        val results = run()
        assertEquals(AiCheckItem.entries.associateWith { AiCheck(AiCheckState.PASSED) }, results)
        // The tool round trip sends the assistant message back unchanged, thinking included, with the tool's answer.
        val second = requests.first { req -> req["messages"]!!.jsonArray.any { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" } }
        val assistant = second["messages"]!!.jsonArray[1].jsonObject
        assertEquals("call it", assistant["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("t1", second["messages"]!!.jsonArray[2].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(4, drawn.length)
        // Stricter services reject a tool schema without a required list, even an empty one.
        val parameters = second["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject
        assertEquals(JsonArray(emptyList()), parameters["required"])
        // Tool and picture checks leave room for an answer after unwanted thinking.
        requests.filter { it["tools"] != null || it["messages"]!!.jsonArray.last().jsonObject["content"] is JsonArray }
            .forEach { assertEquals(4096, it["max_tokens"]!!.jsonPrimitive.int) }
    }

    @Test fun no_tool_call_or_a_wrong_reading_fails_that_check_only() = runTest {
        onTools = { ok("I cannot call tools.") }
        onImage = { ok("I see a picture of digits 0000.") }
        val results = run()
        assertEquals(AiCheck(AiCheckState.FAILED, AiProblem.NO_TOOL_CALL), results[AiCheckItem.TOOLS])
        assertEquals(AiCheck(AiCheckState.FAILED, AiProblem.WRONG_ANSWER), results[AiCheckItem.IMAGE])
        assertEquals(AiCheckState.PASSED, results[AiCheckItem.THINKING]!!.state)
    }

    @Test fun a_refusal_means_unsupported_but_network_trouble_is_undecided() = runTest {
        onImage = { AiHttpResponse(400, """{"error":"image input is not supported"}""") }
        onThinking = { throw SocketTimeoutException() }
        val results = run()
        assertEquals(AiCheck(AiCheckState.FAILED, AiProblem.REJECTED), results[AiCheckItem.IMAGE])
        assertEquals(AiCheck(AiCheckState.UNKNOWN, AiProblem.TIMEOUT), results[AiCheckItem.THINKING])
    }

    @Test fun thinking_without_its_parameters_is_not_configured_and_without_thinking_it_fails() = runTest {
        assertEquals(AiCheck(AiCheckState.NOT_CONFIGURED), run(connection.copy(thinkingOnParams = ""))[AiCheckItem.THINKING])
        onThinking = { ok("42") }
        assertEquals(AiCheck(AiCheckState.FAILED, AiProblem.NO_THINKING), run()[AiCheckItem.THINKING])
    }

    @Test fun without_a_connection_nothing_else_is_tried() = runTest {
        onConnect = { AiHttpResponse(401, "") }
        assertEquals(mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.FAILED, AiProblem.UNAUTHORIZED)), run())
        onConnect = { throw IOException() }
        assertEquals(mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.UNKNOWN, AiProblem.OFFLINE)), run())
        onConnect = { AiHttpResponse(404, "") }
        assertEquals(mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.FAILED, AiProblem.NOT_FOUND)), run())
    }
}
