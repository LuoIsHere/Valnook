package dev.valnook.data.ai

import dev.valnook.domain.ai.AiAccountChoice
import dev.valnook.domain.ai.AiSettings
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** What every request starts with: stable, in a fixed order, and only what the design allows to be sent. */
class AiPromptTest {
    private fun category(id: Long, parent: Long?, name: String, kind: BillCategoryKind = BillCategoryKind.EXPENSE, hidden: Boolean = false) =
        BillCategory(id, kind, parent, null, name, "icon", "grey", id, hidden, null, 1)

    private val categories = listOf(
        category(12, null, "Food"), category(122, 12, "Lunch"), category(121, 12, "Breakfast"),
        category(13, null, "Old", hidden = true), category(131, 13, "Gone"),
        category(30, null, "Salary", BillCategoryKind.INCOME),
    )
    private val accounts = listOf(AiAccountChoice(9, "Synthetic Bank · Card", "USD", false, credit = true),
        AiAccountChoice(4, "Synthetic Bank · Savings", "CNY", true),
        AiAccountChoice(5, "Synthetic Bank · Hidden", "CNY", true, hidden = true))

    @Test fun categories_are_compact_by_id_without_hidden_ones() {
        val text = AiPrompt.categoryList(categories, english = true)
        assertTrue(text.contains("12 Food: 121 Breakfast, 122 Lunch"))
        assertTrue(text.contains("Income categories:\n30 Salary"))
        assertFalse(text.contains("Old"))
        assertFalse(text.contains("Gone"))
    }

    @Test fun every_shown_account_goes_with_favourites_marked_frequent() {
        assertEquals("Accounts (id name currency type, then \"frequent\" for the user's frequently used ones):\n" +
            "4 Synthetic Bank · Savings CNY cash frequent\n9 Synthetic Bank · Card USD credit", AiPrompt.accountList(accounts))
        assertEquals("Accounts: none.", AiPrompt.accountList(emptyList()))
        assertEquals("Accounts: none.", AiPrompt.accountList(accounts.filter { it.hidden }))
    }

    @Test fun a_long_list_keeps_the_favourites_then_the_most_recently_used() {
        // 105 shown accounts: 1 is a favourite used longest ago, 2 was never used, the rest were used in id order.
        val many = (1L..105L).map { AiAccountChoice(it, "Synthetic $it", "CNY", it == 1L, lastUsedMs = if (it == 2L) null else it) } +
            AiAccountChoice(200, "Synthetic hidden", "CNY", true, hidden = true, lastUsedMs = 999)
        val sent = AiPrompt.sentAccounts(many).map { it.id }
        assertEquals(AiPrompt.ACCOUNT_LIMIT, sent.size)
        assertEquals(listOf(1L) + (7L..105L), sent)
        // Up to the limit nothing is dropped.
        assertEquals((1L..100L).toList(), AiPrompt.sentAccounts(many.filter { it.id <= 100 }).map { it.id })
    }

    @Test fun the_rules_say_what_amount_and_discount_mean_and_leave_refunds_to_the_bill() {
        val rules = AiPrompt.fixedRules(AiSettings(), english = false)
        assertTrue(rules.contains("set amount to the price and discount to everything taken off"))
        assertTrue(rules.contains("do not make a draft for the purchase they belong to"))
        assertTrue(rules.contains("make two drafts: the user's own share, not reimbursable"))
        assertTrue(rules.contains("evening (晚上) 19:00"))
        val tool = AiPrompt.billTool.toString()
        assertTrue(tool.contains("price before any discount"))
        assertFalse(tool.contains("amount paid"))
    }

    @Test fun the_parts_come_in_a_fixed_order_with_the_time_last() {
        val settings = AiSettings(myRules = "Coffee shops are drinks.", userNickname = "Sam", aiNickname = "Penny")
        val system = AiPrompt.system(settings, categories, accounts, LocalDateTime.of(2026, 11, 14, 8, 30), "CNY", english = false)
        val order = listOf("Rules v", "Call the user \"Sam\"", "Your name is \"Penny\"", "Coffee shops are drinks.",
            "Expense categories", "Accounts (", "Now: 2026-11-14 08:30, Saturday. Base currency: CNY.")
        assertEquals(order.map { system.indexOf(it) }.sorted(), order.map { system.indexOf(it) })
        assertTrue(order.all { system.contains(it) })
        assertTrue(system.endsWith("Base currency: CNY."))
        assertTrue(system.contains("Simplified Chinese"))
        // Without rules or nicknames the section is left out rather than sent empty.
        assertNull(AiPrompt.personal(AiSettings()))
    }

    @Test fun shared_content_has_its_currency_read() {
        val rules = AiPrompt.fixedRules(AiSettings(), english = false)
        assertTrue(rules.contains("In screenshots, receipts and shared text, set currency on every bill"))
        assertTrue(AiPrompt.SHARE_INTRO.contains("currency"))
    }

    @Test fun chat_changes_only_how_much_is_said() {
        val quiet = AiPrompt.fixedRules(AiSettings(chat = false), english = true)
        val chatty = AiPrompt.fixedRules(AiSettings(chat = true), english = true)
        assertTrue(quiet.contains("one or two sentences"))
        assertTrue(chatty.contains("chat naturally"))
        assertTrue(chatty.contains("Never give investment or financial advice."))
    }

    @Test fun a_bill_request_has_the_draft_tool_and_a_limit_by_thinking() {
        val request = AiPrompt.billRequest("system", listOf(AiPrompt.message("user", "lunch 25")), thinking = false)
        assertEquals("create_bill_drafts", request["tools"]!!.jsonArray.single().jsonObject["function"]!!
            .jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(4096, request["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(16384, AiPrompt.billRequest("s", emptyList(), thinking = true)["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("system", request["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test fun questions_about_bills_bring_the_query_tools_and_their_rules() {
        fun names(request: JsonObject) = request["tools"]?.jsonArray.orEmpty().map {
            it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }
        val off = AiPrompt.fixedRules(AiSettings(), english = false)
        assertTrue(off.contains("You cannot look up the user's bills"))
        assertTrue(off.contains("对话查询"))
        val on = AiPrompt.fixedRules(AiSettings(query = true), english = true, bills = true, queries = true)
        assertTrue(on.contains("call query_summary or query_bills"))
        assertTrue(on.contains("up to 3 rounds"))
        assertFalse(on.contains("You cannot look up the user's bills"))
        // Only questions: no drafts made in conversation, and the user is told where to turn them on.
        val onlyQueries = AiPrompt.fixedRules(AiSettings(billChat = false, query = true), english = false, bills = false, queries = true)
        assertTrue(onlyQueries.contains("Recording bills here is turned off"))
        assertTrue(onlyQueries.contains("对话记账"))
        val conversation = listOf(AiPrompt.message("user", "How much on food this month?"))
        assertEquals(listOf("create_bill_drafts", "query_summary", "query_bills"),
            names(AiPrompt.chatRequest("s", conversation, false, bills = true, queries = true)))
        assertEquals(listOf("query_summary", "query_bills"), names(AiPrompt.chatRequest("s", conversation, false, bills = false, queries = true)))
        // Answering from results needs less room, and once the queries are used up no tool may be called.
        val answering = AiPrompt.chatRequest("s", conversation, false, bills = true, queries = true, toolsOff = true, afterQuery = true)
        assertEquals("none", answering["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(2048, answering["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(16384, AiPrompt.chatRequest("s", conversation, true, true, true, afterQuery = true)["max_tokens"]!!.jsonPrimitive.int)
        // The system message follows the switches unless told otherwise.
        val system = AiPrompt.system(AiSettings(query = true), categories, accounts, LocalDateTime.of(2026, 11, 20, 9, 0), "CNY", true)
        assertTrue(system.contains("call query_summary"))
        assertFalse(AiPrompt.system(AiSettings(query = true), categories, accounts, LocalDateTime.of(2026, 11, 20, 9, 0), "CNY", true,
            queries = false).contains("call query_summary"))
    }
}
