package dev.valnook.data.ai

import dev.valnook.domain.ai.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Requests and replies of an OpenAI-compatible service, against a scripted transport. */
class AiClientTest {
    private val connection = AiConnection("deepseek", "https://api.example.test", "model-a",
        """{"temperature": 0.2, "thinking": {"budget": 10}}""", """{"thinking": {"type": "disabled"}}""",
        """{"thinking": {"type": "enabled"}}""")
    private val sent = mutableListOf<AiHttpRequest>()
    private fun client(reply: (AiHttpRequest) -> AiHttpResponse) = AiClient { sent += it; reply(it) }
    private fun body() = JsonObject(mapOf("messages" to JsonArray(emptyList())))

    @Test fun parameters_lay_over_the_body_extra_first_then_thinking() {
        val client = client { AiHttpResponse(200, "") }
        val off = client.request(connection, body(), thinking = false)
        assertEquals("model-a", off["model"]!!.jsonPrimitive.content)
        assertEquals(0.2, off["temperature"]!!.jsonPrimitive.double, 0.0)
        // Objects under one key merge: the extra budget stays beside the switch.
        assertEquals("disabled", off["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(10, off["thinking"]!!.jsonObject["budget"]!!.jsonPrimitive.int)
        assertEquals("enabled", client.request(connection, body(), thinking = true)["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun the_key_goes_in_the_header_only_when_there_is_one() = runTest {
        val ok = """{"choices":[{"message":{"role":"assistant","content":"OK"}}]}"""
        client { AiHttpResponse(200, ok) }.chat(connection, "test-key", body(), false, 1000)
        assertEquals("https://api.example.test/chat/completions", sent.last().url)
        assertEquals("Bearer test-key", sent.last().headers["Authorization"])
        client { AiHttpResponse(200, ok) }.chat(connection, null, body(), false, 1000)
        assertNull(sent.last().headers["Authorization"])
        assertFalse(sent.last().toString().contains("test-key"))
    }

    @Test fun models_are_listed_sorted() = runTest {
        val models = client { AiHttpResponse(200, """{"data":[{"id":"b"},{"id":"a"},{"id":"a"},{"object":"x"}]}""") }
            .models(connection, null)
        assertEquals(listOf("a", "b"), models)
        assertEquals("https://api.example.test/models", sent.single().url)
    }

    @Test fun errors_say_what_went_wrong() = runTest {
        suspend fun problem(response: () -> AiHttpResponse): AiProblem? = try {
            client { response() }.chat(connection, "k", body(), false, 1000); null
        } catch (error: AiException) { error.problem }
        assertEquals(AiProblem.UNAUTHORIZED, problem { AiHttpResponse(401, "") })
        assertEquals(AiProblem.UNAUTHORIZED, problem { AiHttpResponse(403, "") })
        assertEquals(AiProblem.PAYMENT, problem { AiHttpResponse(402, "") })
        assertEquals(AiProblem.PAYMENT, problem { AiHttpResponse(400, """{"error":{"message":"Insufficient Balance"}}""") })
        assertEquals(AiProblem.NOT_FOUND, problem { AiHttpResponse(404, "") })
        assertEquals(AiProblem.RATE_LIMITED, problem { AiHttpResponse(429, "") })
        assertEquals(AiProblem.SERVER, problem { AiHttpResponse(503, "") })
        assertEquals(AiProblem.REJECTED, problem { AiHttpResponse(400, """{"error":"tools not supported"}""") })
        // A relay that keeps thinking on refuses a history without the thinking; the parameters need changing.
        assertEquals(AiProblem.THINKING_STILL_ON, problem { AiHttpResponse(400,
            """{"error":{"message":"The `content[].thinking` in the thinking mode must be passed back to the API."}}""") })
        assertEquals(AiProblem.BAD_RESPONSE, problem { AiHttpResponse(302, "") })
        assertEquals(AiProblem.BAD_RESPONSE, problem { AiHttpResponse(200, "<html>") })
        assertEquals(AiProblem.OFFLINE, problem { throw IOException() })
        assertEquals(AiProblem.TIMEOUT, problem { throw SocketTimeoutException() })
    }
}
