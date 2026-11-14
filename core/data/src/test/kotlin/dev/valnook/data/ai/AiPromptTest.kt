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
    private val accounts = listOf(AiAccountChoice(9, "Synthetic Bank · Card", "USD", false),
        AiAccountChoice(4, "Synthetic Bank · Savings", "CNY", true))

    @Test fun categories_are_compact_by_id_without_hidden_ones() {
        val text = AiPrompt.categoryList(categories, english = true)
        assertTrue(text.contains("12 Food: 121 Breakfast, 122 Lunch"))
        assertTrue(text.contains("Income categories:\n30 Salary"))
        assertFalse(text.contains("Old"))
        assertFalse(text.contains("Gone"))
    }

    @Test fun only_favourite_accounts_go_unless_there_are_none() {
        assertEquals("Accounts (id name currency):\n4 Synthetic Bank · Savings CNY", AiPrompt.accountList(accounts))
        assertEquals("Accounts (id name currency):\n4 Synthetic Bank · Savings CNY\n9 Synthetic Bank · Card USD",
            AiPrompt.accountList(accounts.map { it.copy(favorite = false) }))
        assertEquals("Accounts: none.", AiPrompt.accountList(emptyList()))
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
        assertEquals(1024, request["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(8192, AiPrompt.billRequest("s", emptyList(), thinking = true)["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("system", request["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
    }
}
