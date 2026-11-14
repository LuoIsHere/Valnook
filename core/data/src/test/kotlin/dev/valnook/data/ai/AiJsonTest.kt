package dev.valnook.data.ai

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiJsonTest {
    @Test fun parameters_are_empty_or_one_json_object() {
        assertTrue(AiJson.validParams(""))
        assertTrue(AiJson.validParams("  "))
        assertTrue(AiJson.validParams("""{"thinking": {"type": "disabled"}}"""))
        assertFalse(AiJson.validParams("[1]"))
        assertFalse(AiJson.validParams("{"))
        assertFalse(AiJson.validParams("\"text\""))
        assertFalse(AiJson.validParams("{\"a\": \"" + "x".repeat(5000) + "\"}"))
    }

    @Test fun a_reply_gives_its_text_thinking_tool_calls_and_usage() {
        val reply = AiJson.reply("""{"choices":[{"message":{"role":"assistant","content":"Done",
            "reasoning_content":"Thinking it over","tool_calls":[{"id":"c1","type":"function",
            "function":{"name":"create_bill_drafts","arguments":"{\"bills\":[]}"}}]}}],
            "usage":{"prompt_tokens":1380,"completion_tokens":152,"total_tokens":1532,"prompt_cache_hit_tokens":1200}}""")!!
        assertEquals("Done", reply.content)
        assertEquals("Thinking it over", reply.reasoning)
        assertEquals(listOf(AiToolCall("c1", "create_bill_drafts", "{\"bills\":[]}")), reply.toolCalls)
        assertEquals(AiUsage(1380, 152, 1532, 1200), reply.usage)
        // The message goes back exactly as it came, unknown fields included.
        assertEquals("Thinking it over", reply.message["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test fun thinking_is_read_from_any_of_the_usual_places() {
        assertEquals("r", AiJson.reply("""{"choices":[{"message":{"content":"a","reasoning":"r"}}]}""")!!.reasoning)
        val tagged = AiJson.reply("""{"choices":[{"message":{"content":"<think>\nstep\n</think>\n\n42"}}]}""")!!
        assertEquals("step", tagged.reasoning)
        assertEquals("42", tagged.content)
        assertNull(AiJson.reply("""{"choices":[{"message":{"content":"42"}}]}""")!!.reasoning)
        assertNull(AiJson.reply("""{"error":"x"}"""))
    }

    @Test fun cached_tokens_come_from_either_field_name() {
        val reply = AiJson.reply("""{"choices":[{"message":{"content":"a"}}],
            "usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12,"prompt_tokens_details":{"cached_tokens":8}}}""")!!
        assertEquals(8L, reply.usage!!.cached)
        assertEquals(AiUsage(20, 4, 24, 8), reply.usage!! + AiUsage(10, 2, 12, null))
    }

    @Test fun merging_keeps_both_sides_of_an_object() {
        val merged = AiJson.merge(AiJson.params("""{"a": {"x": 1}, "b": 1}"""), AiJson.params("""{"a": {"y": 2}, "b": 3}"""))
        assertEquals(AiJson.params("""{"a": {"x": 1, "y": 2}, "b": 3}"""), merged)
    }
}
