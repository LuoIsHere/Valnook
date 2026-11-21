package dev.valnook.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.ai.*
import dev.valnook.data.database.SettingsEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomBills
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

/** Questions about bills against a scripted service: steps, limits, errors, drafts alongside, and a cancel. */
@RunWith(AndroidJUnit4::class)
class AiQueryAssistantTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-11-20T04:00:00Z"), zone)
    private lateinit var db: ValnookDatabase
    private lateinit var chat: AiChatDatabase
    private lateinit var service: RoomAiService
    private lateinit var assistant: RoomAiAssistant
    private val checks = MemoryAiCheckStore()
    private val keys = MemoryAiKeyStore("test-key")
    private val requests = mutableListOf<JsonObject>()
    private val replies = ArrayDeque<suspend () -> AiHttpResponse>()
    private var food = 0L

    private val transport = AiTransport { request ->
        requests += AiJson.format.parseToJsonElement(String(request.body!!)).jsonObject
        (replies.removeFirstOrNull() ?: error("no reply scripted"))()
    }
    private val pictures = object : AiPictureSource, AiPicturePins {
        override suspend fun forModel(key: String) = byteArrayOf(1)
        override suspend fun thumbnail(key: String) = byteArrayOf(2)
        override fun pin(keys: Collection<String>) {}
        override suspend fun release(keys: Collection<String>) {}
    }

    @Before fun prepare() = runBlocking {
        db = ValnookDatabase.inMemory(context)
        if (db.overview().settings() == null) db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        chat = AiChatDatabase.inMemory(context)
        val client = AiClient(transport)
        service = RoomAiService(db, keys, checks, clock, { true }, client)
        service.saveConnection(AiConnection(null, "https://api.example.test", "model-a"), null)
        val connection = service.observeSettings().first().connection
        checks.save(AiCheckReport(connection.fingerprint, clock.millis(), mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED),
            AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED))))
        service.setEnabled(true)
        service.saveQuery(true)
        val bills = RoomBills(db)
        val queries = AiBillQueries(bills, BillCounter(bills, RoomSettings(db, clock).observeSettings(),
            RoomOverview(db).observeFxContext(), zone), zone)
        assistant = RoomAiAssistant(db, chat, service, keys, clock, { true }, pictures, pictures, client, queries)
        // Synthetic November: two lunches and a salary.
        val commands = RoomFinancialCommands(db, clock)
        val bank = commands.execute(SaveAccount(id(), null, null, "Synthetic Bank", "", listOf(
            CashBalanceChange("CNY", 1_000_000, null, name = "A")))).id
        val cash = RoomOverview(db).snapshot().cash.single { it.account_id == bank }.id
        food = db.openHelper.readableDatabase.query("SELECT id FROM bill_categories WHERE builtin_key='food'")
            .use { it.moveToFirst(); it.getLong(0) }
        fun at(day: Int) = LocalDate.of(2026, 11, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        commands.execute(SaveBill(id(), null, null, BillDraft(BillType.EXPENSE, at(3), 3_500, "CNY", 0, cash, 3_500,
            categoryId = food, note = "Synthetic lunch")))
        commands.execute(SaveBill(id(), null, null, BillDraft(BillType.EXPENSE, at(8), 4_200, "CNY", 0, cash, 4_200,
            categoryId = food, note = "Synthetic lunch")))
        Unit
    }

    @After fun close() { db.close(); chat.close() }

    private fun id() = UUID.randomUUID().toString()
    private fun call(id: String, name: String, arguments: String) =
        """{"id":"$id","type":"function","function":{"name":"$name","arguments":${JsonPrimitive(arguments)}}}"""
    private fun calls(vararg calls: String): suspend () -> AiHttpResponse = {
        AiHttpResponse(200, """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
            "tool_calls":[${calls.joinToString(",")}]}}],"usage":{"prompt_tokens":100,"completion_tokens":10,"total_tokens":110}}""")
    }
    private fun text(words: String): suspend () -> AiHttpResponse = {
        AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"$words"}}],
            "usage":{"prompt_tokens":200,"completion_tokens":20,"total_tokens":220}}""")
    }
    private val november = """"bill_type":"EXPENSE","from":"2026-11-01","to":"2026-11-30""""
    private fun toolNames(request: JsonObject) = request["tools"]?.jsonArray.orEmpty()
        .map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
    private fun sent(request: JsonObject) = request["messages"]!!.jsonArray.map { it.jsonObject }
    private suspend fun messages(id: Long) = assistant.observeMessages(id).first()

    @Test fun one_step_answers_from_the_full_result_and_later_rounds_get_a_summary() = runBlocking {
        replies += calls(call("q1", "query_summary", """{$november,"group_by":"CATEGORY"}"""))
        replies += text("Food came to 77.00.")
        val id = assistant.send(null, "What did food cost this month?", emptyList())
        assertEquals(2, requests.size)
        assertEquals(listOf("create_bill_drafts", "query_summary", "query_bills", "query_distribution"), toolNames(requests[0]))
        assertEquals(4096, requests[0]["max_tokens"]!!.jsonPrimitive.int)
        // The second request has the reply and its result in full, and less room to answer in.
        val second = sent(requests[1])
        assertEquals(listOf("system", "user", "assistant", "tool"), second.map { it["role"]!!.jsonPrimitive.content })
        val result = second[3]["content"]!!.jsonPrimitive.content
        assertTrue(result, result.contains("Total: 77.00 (2 bills)"))
        assertTrue(result, result.contains("$food "))
        assertEquals(2048, requests[1]["max_tokens"]!!.jsonPrimitive.int)
        assertNull(requests[1]["tool_choice"])
        val answer = messages(id).last()
        assertEquals("Food came to 77.00.", answer.text)
        assertTrue(answer.drafts.isEmpty())
        assertEquals(AiTurnUsage(300, 30, 330, null, 2), answer.usage)
        val record = answer.queries.single()
        assertEquals(1, record.step)
        assertEquals(1, record.rows)
        assertEquals(AiQueryGroup.CATEGORY, record.call!!.group)
        assertEquals(LocalDate.of(2026, 11, 30), record.call!!.to)
        assertNull(assistant.observeQuerying().first())
        // The next round sends that step again with only a summary of its result.
        replies += text("You are welcome.")
        assistant.send(id, "Thanks", emptyList())
        val later = sent(requests.last())
        assertEquals(listOf("system", "user", "assistant", "tool", "assistant", "user"), later.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("Gave 1 row(s); not repeated here.", later[3]["content"]!!.jsonPrimitive.content)
    }

    @Test fun after_three_steps_the_model_must_answer_and_extra_calls_are_refused() = runBlocking {
        val summary = """{$november}"""
        replies += calls(*(1..5).map { call("a$it", "query_summary", summary) }.toTypedArray())
        replies += calls(call("b1", "query_bills", """{"from":"2026-11-01","to":"2026-11-30"}"""))
        replies += calls(call("c1", "query_summary", summary))
        replies += text("That is what I found.")
        val id = assistant.send(null, "Tell me everything", emptyList())
        assertEquals(4, requests.size)
        // The fourth request forbids tools: three steps were used.
        assertEquals("none", requests[3]["tool_choice"]!!.jsonPrimitive.content)
        assertTrue(listOf(0, 1, 2).all { requests[it]["tool_choice"] == null })
        val first = sent(requests[1]).filter { it["role"]!!.jsonPrimitive.content == "tool" }
        assertEquals(5, first.size)
        assertTrue(first[4]["content"]!!.jsonPrimitive.content.startsWith("Not run: at most 4 queries"))
        val records = messages(id).last().queries
        assertEquals(listOf(1, 1, 1, 1, 1, 2, 3), records.map { it.step })
        assertEquals(listOf(false, false, false, false, true, false, false), records.map { it.error })
        assertEquals(2, records[5].rows)
        assertEquals("That is what I found.", messages(id).last().text)
    }

    @Test fun a_wrong_argument_goes_back_to_be_corrected_and_an_empty_result_is_said() = runBlocking {
        replies += calls(call("q1", "query_summary", """{$november,"category_id":999999}"""))
        replies += calls(call("q2", "query_bills", """{"from":"2026-10-01","to":"2026-10-31"}"""))
        replies += text("Nothing in October.")
        val id = assistant.send(null, "What did I buy in October?", emptyList())
        val error = sent(requests[1]).last()["content"]!!.jsonPrimitive.content
        assertEquals("Error: category_id 999999 is not a listed category. Correct it and call again.", error)
        val empty = sent(requests[2]).last()["content"]!!.jsonPrimitive.content
        assertTrue(empty, empty.contains("No bills match."))
        val records = messages(id).last().queries
        assertEquals(listOf(AiQueryRecord(1, null, 0, true)), records.take(1))
        assertEquals(0, records[1].rows)
        assertFalse(records[1].error)
    }

    @Test fun a_bill_and_a_question_together_give_a_draft_and_an_answer() = runBlocking {
        replies += calls(call("b1", "create_bill_drafts", """{"message":"Draft made, please confirm.","bills":[{"type":"EXPENSE","amount":"30","note":"Synthetic coffee"}]}"""),
            call("q1", "query_summary", """{$november}"""))
        replies += text("Noted the coffee. Before it, this month came to 77.00.")
        val id = assistant.send(null, "Coffee 30. How much this month so far?", emptyList())
        val results = sent(requests[1]).filter { it["role"]!!.jsonPrimitive.content == "tool" }
        assertTrue(results[0]["content"]!!.jsonPrimitive.content.startsWith("Drafts made"))
        assertTrue(results[1]["content"]!!.jsonPrimitive.content.contains("Total: 77.00 (2 bills)"))
        val answer = messages(id).last()
        // The answer already speaks of the draft: the draft call's own words are not added again.
        assertEquals("Noted the coffee. Before it, this month came to 77.00.", answer.text)
        assertEquals(listOf("d1"), answer.drafts.map { it.label })
        assertEquals(3_000L, answer.drafts.single().draft.amountMinor)
        assertEquals(1, answer.queries.size)
        // Later rounds hear what became of the draft through the step that made it.
        replies += text("Ok.")
        assistant.send(id, "Ok", emptyList())
        val later = sent(requests.last()).filter { it["role"]!!.jsonPrimitive.content == "tool" }
        assertTrue(later[0]["content"]!!.jsonPrimitive.content.contains("d1 waiting"))
        assertEquals("Gave 1 row(s); not repeated here.", later[1]["content"]!!.jsonPrimitive.content)
    }

    @Test fun cancelling_after_a_query_keeps_the_usage_and_no_answer() = runBlocking {
        replies += calls(call("q1", "query_summary", """{$november,"group_by":"DAY"}"""))
        replies += { awaitCancellation() }
        var conversation = 0L
        val job = launch(Dispatchers.Default) { assistant.send(null, "Daily spending?", emptyList()) { c, _ -> conversation = c } }
        withTimeout(10_000) { while (requests.size < 2) delay(20) }
        // While the model works on the result, the page can say what was looked up.
        val progress = assistant.observeQuerying().first()!!
        assertEquals(AiQueryGroup.DAY, progress.call!!.group)
        assertEquals(conversation, progress.conversationId)
        job.cancelAndJoin()
        val last = messages(conversation).last()
        assertEquals(AiMessageKind.ERROR, last.kind)
        assertNull(last.problem)
        assertEquals(1, last.usage!!.calls)
        assertNull(assistant.observeQuerying().first())
    }

    @Test fun the_tools_follow_the_switches() = runBlocking {
        service.saveQuery(false)
        replies += text("Turn on Ask about bills in Settings.")
        assistant.send(null, "How much this month?", emptyList())
        assertEquals(listOf("create_bill_drafts"), toolNames(requests.last()))
        // Only questions: the chat is there, without the draft tool.
        service.saveQuery(true)
        service.saveFeatures(billChat = false, shareText = true, shareImage = false, thinking = false, chat = false, sendHabits = false)
        assertTrue(service.observeFeatures().first().chatEntry)
        replies += text("Ask away.")
        assistant.send(null, "Hi", emptyList())
        assertEquals(listOf("query_summary", "query_bills", "query_distribution"), toolNames(requests.last()))
        assertTrue(sent(requests.last()).first()["content"]!!.jsonPrimitive.content.contains("Recording bills here is turned off"))
    }
}
