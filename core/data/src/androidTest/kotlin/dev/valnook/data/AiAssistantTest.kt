package dev.valnook.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.ai.*
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Conversations and shares against a scripted service: drafts, corrections, retries, usage and held pictures. */
@RunWith(AndroidJUnit4::class)
class AiAssistantTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var nowMs = Instant.parse("2026-11-14T04:00:00Z").toEpochMilli()
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId?) = this
        override fun instant(): Instant = Instant.ofEpochMilli(nowMs)
    }
    private lateinit var db: ValnookDatabase
    private lateinit var chat: AiChatDatabase
    private lateinit var service: RoomAiService
    private lateinit var assistant: RoomAiAssistant
    private val checks = MemoryAiCheckStore()
    private val keys = MemoryAiKeyStore("test-key")
    private val requests = mutableListOf<JsonObject>()
    private val replies = ArrayDeque<() -> AiHttpResponse>()
    private val pinned = mutableMapOf<String, Int>()
    private val released = mutableListOf<String>()

    private val transport = AiTransport { request ->
        requests += AiJson.format.parseToJsonElement(String(request.body!!)).jsonObject
        (replies.removeFirstOrNull() ?: error("no reply scripted"))()
    }
    private val pictures = object : AiPictureSource, AiPicturePins {
        override suspend fun forModel(key: String) = byteArrayOf(1)
        override suspend fun thumbnail(key: String) = byteArrayOf(2)
        override fun pin(keys: Collection<String>) = keys.forEach { pinned.merge(it, 1, Int::plus) }
        override suspend fun release(keys: Collection<String>) {
            keys.forEach { key -> pinned.computeIfPresent(key) { _, n -> (n - 1).takeIf { it > 0 } } }
            released += keys
        }
    }

    @Before fun prepare() = runBlocking {
        db = ValnookDatabase.inMemory(context)
        chat = AiChatDatabase.inMemory(context)
        val client = AiClient(transport)
        service = RoomAiService(db, keys, checks, clock, { true }, client)
        service.saveConnection(AiConnection(null, "https://api.example.test", "model-a"), null)
        val connection = service.observeSettings().first().connection
        checks.save(AiCheckReport(connection.fingerprint, nowMs, mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED),
            AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED), AiCheckItem.IMAGE to AiCheck(AiCheckState.PASSED))))
        service.setEnabled(true)
        service.saveFeatures(billChat = true, shareText = true, shareImage = true, thinking = false, chat = false, sendHabits = false)
        assistant = RoomAiAssistant(db, chat, service, keys, clock, { true }, pictures, pictures, client)
    }

    @After fun close() { db.close(); chat.close() }

    private fun toolReply(arguments: String, id: String = "c1", usage: String = """{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120}""") = {
        AiHttpResponse(200, """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
            "reasoning_content":"thought","tool_calls":[{"id":"$id","type":"function","function":{"name":"create_bill_drafts",
            "arguments":${JsonPrimitive(arguments)}}}]}}],"usage":$usage}""")
    }
    private fun textReply(text: String) = { AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"$text"}}]}""") }
    private suspend fun messages(id: Long) = assistant.observeMessages(id).first()
    private fun image(c: Char) = BillImage(c.toString().repeat(64), 100, 10, 10)

    @Test fun bills_become_drafts_and_a_correction_replaces_only_a_waiting_one() = runBlocking {
        replies += toolReply("""{"message":"Two bills","bills":[{"type":"EXPENSE","amount":"8","note":"Breakfast"},
            {"type":"EXPENSE","amount":"68","note":"Lunch","reimbursable":true}]}""")
        val id = assistant.send(null, "Breakfast 8, lunch 68 to be paid back", emptyList())
        val first = messages(id)
        assertEquals(listOf(AiMessageKind.USER, AiMessageKind.ASSISTANT), first.map { it.kind })
        val reply = first[1]
        assertEquals("Two bills", reply.text)
        assertEquals("thought", reply.reasoning)
        assertEquals(listOf("d1", "d2"), reply.drafts.map { it.label })
        assertEquals(listOf(800L, 6800L), reply.drafts.map { it.draft.amountMinor })
        assertTrue(reply.drafts[1].draft.reimbursable)
        assertEquals(AiTurnUsage(100, 20, 120, null, 1), reply.usage)
        assertEquals("Breakfast 8, lunch 68…".take(16), assistant.observeConversations().first().single().title.take(16))
        assertTrue(assistant.observePendingDrafts().first())
        assertEquals(id, assistant.conversationToContinue())

        // d1 is saved; the model then tries to change both.
        assistant.confirmed(reply.drafts[0].id, 55)
        replies += toolReply("""{"message":"Changed","bills":[{"draft_id":"d1","type":"EXPENSE","amount":"9"},
            {"draft_id":"d2","type":"EXPENSE","amount":"68","note":"Dinner"}]}""", id = "c2")
        assistant.send(id, "Breakfast was 9 and the second was dinner", emptyList())
        val sent = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "tool", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("thought", sent[2]["reasoning_content"]!!.jsonPrimitive.content)
        val status = sent[3]["content"]!!.jsonPrimitive.content
        assertTrue(status, status.contains("d1 saved by the user") && status.contains("d2 waiting"))
        val after = messages(id)
        val change = after.last()
        assertEquals(listOf(55L), change.confirmedBills)
        assertEquals(listOf("d2"), change.drafts.map { it.label })
        assertEquals("Dinner", change.drafts.single().draft.note)
        assertEquals(AiDraftState.REPLACED, after[1].drafts[1].state)
        assertEquals(AiDraftState.CONFIRMED, after[1].drafts[0].state)
        assertEquals(55L, after[1].drafts[0].billId)
        assertEquals(240L, assistant.observeConversations().first().single().tokens)

        // A reply without the tool is shown as it is, marked as having no drafts.
        replies += textReply("Anything else?")
        assistant.send(id, "Thanks", emptyList())
        assertTrue(messages(id).last().noDrafts)
        assertEquals(AiUsageSummary(3, 240, 200, 40), assistant.observeUsage().first())
    }

    @Test fun drafts_that_cannot_be_read_are_asked_for_once_more() = runBlocking {
        replies += toolReply("""{"bills":[{"type":""")
        replies += toolReply("""{"bills":[{"type":"INCOME","amount":"100"}]}""")
        val id = assistant.send(null, "Salary 100", emptyList())
        val reply = messages(id).last()
        assertEquals(BillType.INCOME, reply.drafts.single().draft.type)
        assertEquals(AiTurnUsage(200, 40, 240, null, 2), reply.usage)

        replies += toolReply("""{"bills":[""")
        replies += toolReply("""not json""")
        assistant.send(id, "Again", emptyList())
        val error = messages(id).last()
        assertEquals(AiMessageKind.ERROR, error.kind)
        assertEquals(AiProblem.UNREADABLE_DRAFTS, error.problem)
        assertEquals(2, error.usage!!.calls)
    }

    @Test fun an_error_is_kept_until_the_message_is_sent_again() = runBlocking {
        replies += { AiHttpResponse(401, "") }
        val id = assistant.send(null, "Taxi 32", emptyList())
        assertEquals(AiProblem.UNAUTHORIZED, messages(id).last().problem)
        replies += toolReply("""{"bills":[{"type":"EXPENSE","amount":"32"}]}""")
        assistant.retry(id)
        assertEquals(listOf(AiMessageKind.USER, AiMessageKind.ASSISTANT), messages(id).map { it.kind })
        // The message went once more, alone: the error was not history.
        assertEquals(listOf("system", "user"), requests.last()["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        // An answered message is not sent again.
        assistant.retry(id)
        assertEquals(2, requests.size)
    }

    @Test fun a_share_is_read_once_without_a_conversation() = runBlocking {
        replies += { AiHttpResponse(400, """{"error":"tool_choice is not supported"}""") }
        replies += toolReply("""{"message":"One bill","bills":[{"type":"EXPENSE","amount":"12.5"}]}""")
        val result = assistant.recognize("Paid 12.50 at a shop", emptyList())
        assertEquals("One bill", result.message)
        assertEquals(1250L, result.drafts.single().amountMinor)
        assertTrue(result.toolCalled)
        // First with the tool required and thinking off, then without requiring it.
        assertEquals("create_bill_drafts", requests[0]["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(requests[1]["tool_choice"])
        val user = requests[1]["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(user, user.startsWith(AiPrompt.SHARE_INTRO) && user.endsWith("Paid 12.50 at a shop"))
        assertTrue(assistant.observeConversations().first().isEmpty())
        assertEquals(2, assistant.observeUsage().first().requests)

        replies += { AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"No bill here"}}]}""") }
        val none = assistant.recognize("Hello", emptyList())
        assertFalse(none.toolCalled)
        assertEquals("No bill here", none.message)
    }

    @Test fun pictures_of_waiting_drafts_stay_held_until_settled_or_expired() = runBlocking {
        replies += toolReply("""{"bills":[{"type":"EXPENSE","amount":"1"},{"type":"EXPENSE","amount":"2"}]}""")
        val id = assistant.send(null, "", listOf(image('a'), image('b')))
        val sent = requests.last()["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "image_url", "image_url"), sent.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        val user = messages(id).first()
        assertEquals(2, user.pictures.size)
        val cards = messages(id).last().drafts
        assertEquals(listOf(listOf(image('a')), listOf(image('b'))), cards.map { it.images })
        assertEquals(mapOf("a".repeat(64) to 1, "b".repeat(64) to 1), pinned)
        assertEquals("Pictures", assistant.observeConversations().first().single().title)

        assistant.discard(cards[0].id)
        assertEquals(mapOf("b".repeat(64) to 1), pinned)
        // A correction keeps the pictures of the card it replaces.
        replies += toolReply("""{"bills":[{"draft_id":"d2","type":"EXPENSE","amount":"3"}]}""")
        assistant.send(id, "The second was 3", emptyList())
        assertEquals(listOf(image('b')), messages(id).last().drafts.single().images)
        assertEquals(mapOf("b".repeat(64) to 1), pinned)
        // The earlier pictures are mentioned, not sent again.
        val history = requests.last()["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(history, history.contains("2 picture(s) sent earlier"))

        // After a restart only waiting drafts are held again; an expired conversation lets them go.
        pinned.clear()
        assistant.restorePins()
        assertEquals(mapOf("b".repeat(64) to 1), pinned)
        nowMs += (AiRules.KEEP_DAYS + 1) * 24 * 60 * 60 * 1000
        assertNull(assistant.conversationToContinue())
        assistant.expire()
        assertTrue(pinned.isEmpty())
        assertTrue(assistant.observeConversations().first().isEmpty())
        assertFalse(assistant.observePendingDrafts().first())
    }

    @Test fun nothing_is_sent_while_the_features_are_off() = runBlocking {
        service.saveConnection(AiConnection(null, "https://api.example.test", "model-b"), null)
        assertEquals(AiFeatures(), service.observeFeatures().first())
        assertThrows(IllegalStateException::class.java) { runBlocking { assistant.send(null, "Lunch", emptyList()) } }
        assertThrows(IllegalStateException::class.java) { runBlocking { assistant.recognize("Lunch", emptyList()) } }
        assertTrue(requests.isEmpty())
    }
}
