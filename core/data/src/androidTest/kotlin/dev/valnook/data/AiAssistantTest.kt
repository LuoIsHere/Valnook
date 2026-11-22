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
        // Said in words without a currency: the usual case, not flagged.
        assertTrue(reply.drafts.none { AiDraftFlag.CURRENCY in it.draft.flags })
        assertEquals(AiTurnUsage(100, 20, 120, null, 1), reply.usage)
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

    @Test fun every_shown_account_is_offered_and_one_named_is_read_whether_a_favourite_or_not() = runBlocking {
        val commands = dev.valnook.data.transaction.RoomFinancialCommands(db, clock)
        fun uuid() = java.util.UUID.randomUUID().toString()
        commands.execute(dev.valnook.domain.repository.SaveAccount(uuid(), null, null, "Synthetic Bank", "", listOf(
            dev.valnook.domain.repository.CashBalanceChange("CNY", 1_234_567, null, name = "Debit"),
            dev.valnook.domain.repository.CashBalanceChange("CNY", 0, null, name = "Spare"))))
        commands.execute(dev.valnook.domain.repository.SaveAccount(uuid(), null, null, "Synthetic Wallet", "", listOf(
            dev.valnook.domain.repository.CashBalanceChange("CNY", 5_000, null, name = "Pocket"))))
        val cash = dev.valnook.data.repository.RoomOverview(db).snapshot().cash
        val debit = cash.single { it.name == "Debit" }.id
        val spare = cash.single { it.name == "Spare" }.id
        val pocket = cash.single { it.name == "Pocket" }.id
        commands.execute(dev.valnook.domain.repository.SaveBillFavorites(uuid(), listOf(debit)))
        // Spare is hidden from the accounts page.
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET show_on_accounts_page=0 WHERE id=?", arrayOf(spare))
        replies += toolReply("""{"message":"ok","bills":[{"type":"EXPENSE","amount":"5","note":"Coffee","account_id":$pocket},
            {"type":"EXPENSE","amount":"6","note":"Tea","account_id":$spare}]}""")
        val id = assistant.send(null, "Coffee 5 from the pocket, tea 6 from spare", emptyList())
        val lines = requests.last()["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonPrimitive.content.lines()
        assertTrue(lines.toString(), "$debit Synthetic Bank · Debit CNY cash frequent" in lines)
        // Not a favourite, still offered and read.
        assertTrue(lines.toString(), "$pocket Synthetic Wallet · Pocket CNY cash" in lines)
        assertTrue(lines.none { it.contains("Spare") || it.contains("12345") })
        val drafts = messages(id).last().drafts.map { it.draft }
        assertEquals(pocket, drafts[0].cashAccountId)
        // A hidden account was not offered, so its id is not taken.
        assertNotEquals(spare, drafts[1].cashAccountId)
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

    @Test fun a_share_joins_the_conversation_and_is_read_as_a_share() = runBlocking {
        service.saveFeatures(billChat = true, shareText = true, shareImage = true, thinking = false, chat = true, sendHabits = false)
        replies += textReply("Hello")
        val id = assistant.send(null, "Hi", emptyList())
        // The service refuses a required tool at first: asked again without requiring it.
        replies += { AiHttpResponse(400, """{"error":"tool_choice is not supported"}""") }
        replies += toolReply("""{"message":"One bill","bills":[{"type":"EXPENSE","amount":"12.5"}]}""")
        var stored: Pair<Long, Long>? = null
        assertEquals(id, assistant.sendShared(id, "Paid 12.50 at a shop", emptyList()) { c, m -> stored = c to m })
        assertEquals(id, stored!!.first)
        assertEquals(messages(id).first { it.shared }.id, stored!!.second)
        assertEquals("create_bill_drafts", requests[1]["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertNull(requests[2]["tool_choice"])
        val sent = requests[2]["messages"]!!.jsonArray.map { it.jsonObject }
        // As a message in the conversation: the earlier round goes along; the share is introduced as one.
        assertEquals(listOf("system", "user", "assistant", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
        val user = sent.last()["content"]!!.jsonPrimitive.content
        assertTrue(user, user.startsWith(AiPrompt.SHARE_INTRO) && user.endsWith("Paid 12.50 at a shop"))
        val shown = messages(id)
        val share = shown.first { it.kind == AiMessageKind.USER && it.text.startsWith("Paid") }
        assertTrue(share.shared)
        assertFalse(shown.first().shared)
        val draft = shown.last().drafts.single().draft
        assertEquals(1250L, draft.amountMinor)
        // A share's currency is to be read off it; none given is flagged.
        assertTrue(AiDraftFlag.CURRENCY in draft.flags)
        assertEquals(3, assistant.observeUsage().first().requests)

        // Later rounds still say it was shared.
        replies += textReply("OK")
        assistant.send(id, "Thanks", emptyList())
        val again = requests.last()["messages"]!!.jsonArray.map { it.jsonObject }
        assertTrue(again.any { it["role"]!!.jsonPrimitive.content == "user" &&
            (it["content"] as? JsonPrimitive)?.content?.startsWith(AiPrompt.SHARE_INTRO) == true })
    }

    @Test fun a_shared_message_is_never_read_with_thinking() = runBlocking {
        service.saveConnection(AiConnection(null, "https://api.example.test", "model-a", "", """{"thinking":"off"}""",
            """{"thinking":"on"}"""), null)
        val connection = service.observeSettings().first().connection
        checks.save(AiCheckReport(connection.fingerprint, nowMs, AiCheckItem.entries.associateWith { AiCheck(AiCheckState.PASSED) }))
        service.saveFeatures(billChat = true, shareText = true, shareImage = true, thinking = true, chat = false, sendHabits = false)
        replies += toolReply("""{"bills":[]}""")
        assistant.sendShared(null, "Receipt", emptyList())
        assertEquals("off", requests.last()["thinking"]!!.jsonPrimitive.content)
        replies += textReply("Hi")
        assistant.send(assistant.conversationToContinue(), "Hello", emptyList())
        assertEquals("on", requests.last()["thinking"]!!.jsonPrimitive.content)
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
        assertThrows(IllegalStateException::class.java) { runBlocking { assistant.sendShared(null, "Lunch", emptyList()) } }
        assertTrue(requests.isEmpty())
    }

    @Test fun a_reply_cut_off_by_the_output_limit_is_an_error_and_not_asked_again() = runBlocking {
        replies += { AiHttpResponse(200, """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"",
            "reasoning_content":"thinking on and on","tool_calls":[{"id":"c1","type":"function","function":{"name":"create_bill_drafts",
            "arguments":"{\"bills\":[{\"type\":"}}]}}],"usage":{"prompt_tokens":100,"completion_tokens":4096,"total_tokens":4196}}""") }
        val id = assistant.send(null, "Lunch 30", emptyList())
        val error = messages(id).last()
        assertEquals(AiProblem.TRUNCATED, error.problem)
        assertEquals(1, requests.size)
        assertEquals(1, error.usage!!.calls)
        // Sent again by the user, it may finish.
        replies += toolReply("""{"bills":[{"type":"EXPENSE","amount":"30"}]}""")
        assistant.retry(id)
        assertEquals(AiMessageKind.ASSISTANT, messages(id).last().kind)
    }

    @Test fun a_reply_with_neither_words_nor_drafts_is_an_error() = runBlocking {
        replies += { AiHttpResponse(200, """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"",
            "reasoning_content":"only thought"}}]}""") }
        val id = assistant.send(null, "Lunch 30", emptyList())
        assertEquals(AiProblem.EMPTY_REPLY, messages(id).last().problem)
        assertEquals(1, requests.size)
    }

    @Test fun thinking_can_be_switched_for_a_message_where_it_is_available() = runBlocking {
        fun sent() = requests.last()["thinking"]?.jsonPrimitive?.content to requests.last()["max_tokens"]!!.jsonPrimitive.int
        // Not available yet: asking for it changes nothing.
        replies += textReply("Hi")
        val id = assistant.send(null, "Hello", emptyList(), thinking = true)
        assertEquals(null to AiRules.MAX_TOKENS, sent())

        service.saveConnection(AiConnection(null, "https://api.example.test", "model-a", "", """{"thinking":"off"}""",
            """{"thinking":"on"}"""), null)
        val connection = service.observeSettings().first().connection
        checks.save(AiCheckReport(connection.fingerprint, nowMs, AiCheckItem.entries.associateWith { AiCheck(AiCheckState.PASSED) }))
        replies += textReply("Hi"); assistant.send(id, "Hello", emptyList())
        assertEquals("off" to AiRules.MAX_TOKENS, sent())
        replies += textReply("Hi"); assistant.send(id, "Hello", emptyList(), thinking = true)
        assertEquals("on" to AiRules.MAX_TOKENS_THINKING, sent())
        // On by default: a message can still go without it, and so can its retry.
        service.saveFeatures(billChat = true, shareText = true, shareImage = true, thinking = true, chat = false, sendHabits = false)
        replies += textReply("Hi"); assistant.send(id, "Hello", emptyList())
        assertEquals("on" to AiRules.MAX_TOKENS_THINKING, sent())
        replies += { AiHttpResponse(401, "") }; assistant.send(id, "Hello", emptyList(), thinking = false)
        assertEquals("off" to AiRules.MAX_TOKENS, sent())
        replies += textReply("Hi"); assistant.retry(id, thinking = false)
        assertEquals("off" to AiRules.MAX_TOKENS, sent())
    }
}
