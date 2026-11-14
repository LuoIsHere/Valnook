package dev.valnook.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.ai.AiHttpResponse
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveNavigationConfiguration
import dev.valnook.feature.ai.AiNotices
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import javax.inject.Inject

/** Bookkeeping by chat against a scripted service, with synthetic accounts only; nothing reaches the network. */
@HiltAndroidTest
class AiChatUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = sessions.session.value.graph
    private var cashId = 0L
    private var calls = 0

    @Before fun seed() {
        hilt.inject()
        ScriptedAiTransport.reset()
        AiNotices.clear(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成银行", "",
                listOf(CashBalanceChange("CNY", 100_000, null, name = "合成储蓄卡")))).id
            cashId = db.cash().accountIds(account).single()
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveNavigationConfiguration(settings.revision, NavigationConfiguration(
                NavigationItemId.entries, setOf(NavigationItemId.BILLS, NavigationItemId.ACCOUNTS, NavigationItemId.SETTINGS))))
            val ai = graph.ai!!
            ai.saveConnection(AiConnection(null, "https://ai.example.test", "synthetic-model"), "synthetic-key")
            val fingerprint = ai.observeSettings().first().connection.fingerprint
            TestGraphs.real.aiChecks.save(AiCheckReport(fingerprint, 0, mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED),
                AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED))))
            ai.setEnabled(true)
        }
        waitTag("nav-bills")
        rule.onNodeWithTag("nav-bills").performClick()
        waitTag("bills-list")
    }

    @After fun clean() = ScriptedAiTransport.reset()

    private fun waitTag(tag: String, timeout: Long = 8_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun gone(tag: String, timeout: Long = 8_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    private fun category(key: String) = db.openHelper.readableDatabase
        .query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }

    private fun balance() = runBlocking { db.cash().cashAccount(cashId)!!.balance_minor }

    private fun drafts(message: String, vararg bills: String) {
        val id = "call-${++calls}"
        val arguments = """{"message":"$message","bills":[${bills.joinToString(",")}]}"""
        ScriptedAiTransport.reply {
            AiHttpResponse(200, """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"$id","type":"function","function":{"name":"create_bill_drafts","arguments":${JsonPrimitive(arguments)}}}]}}],
                "usage":{"prompt_tokens":900,"completion_tokens":60,"total_tokens":960}}""")
        }
    }

    private fun openChat() {
        waitTag("ai-chat-entry")
        rule.onNodeWithTag("ai-chat-entry").performClick()
        waitTag("ai-chat")
    }

    private fun send(text: String) {
        rule.onNode(hasSetTextAction() and hasTestTag("ai-chat-input")).performTextInput(text)
        rule.onNodeWithTag("ai-chat-send").performClick()
    }

    private fun tagStarting(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }

    @Test fun a_sentence_becomes_a_draft_that_is_saved_and_taken_back() {
        openChat()
        waitTag("ai-chat-examples")
        drafts("记好了", """{"type":"EXPENSE","amount":"25","category_id":${category("food")},
            "sub_category_id":${category("food.lunch")},"account_id":$cashId,"note":"合成午饭"}""")
        send("午饭 25")
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithText("记好了").assertExists()
        rule.onNodeWithText("合成午饭").assertExists()
        rule.onNodeWithTag("ai-chat-usage").assertTextContains("960", substring = true)
        // The request carried the account list and the words, and nothing else of the user's.
        val messages = ScriptedAiTransport.requests.single()!!["messages"]!!.jsonArray
        assertTrue(messages.first().jsonObject["content"]!!.jsonPrimitive.content.contains("合成银行 · 合成储蓄卡"))
        assertEquals("午饭 25", messages.last().jsonObject["content"]!!.jsonPrimitive.content)

        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        waitTag("ai-draft-d1-confirmed")
        assertEquals(97_500L, balance())
        rule.onNodeWithTag("ai-draft-d1-undo").performClick()
        waitTag("ai-draft-d1-pending")
        assertEquals(100_000L, balance())
        rule.onNodeWithTag("ai-draft-d1-discard").performClick()
        waitTag("ai-draft-d1-discarded")
        // Nothing waits any more: the button above "+" has no dot.
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("bills-list")
        waitTag("ai-chat-entry")
        rule.onNodeWithTag("ai-chat-entry-dot").assertDoesNotExist()
    }

    @Test fun the_first_message_shows_at_once_and_the_reply_is_awaited_in_the_ai_bubble() {
        openChat()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val arguments = """{"message":"","bills":[{"type":"EXPENSE","amount":"25","category_id":${category("food")}}]}"""
        ScriptedAiTransport.reply {
            release.await()
            AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"w1",
                "type":"function","function":{"name":"create_bill_drafts","arguments":${JsonPrimitive(arguments)}}}]}}]}""")
        }
        send("合成午饭 25")
        // A new conversation: the words are there before any reply, and the reply is awaited in the AI's bubble.
        waitTag("ai-chat-working")
        rule.waitUntil(8_000) { rule.onAllNodesWithText("合成午饭 25").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ai-draft-d1-pending").assertDoesNotExist()
        release.complete(Unit)
        waitTag("ai-draft-d1-pending")
        gone("ai-chat-working")
    }

    @Test fun a_draft_without_an_amount_is_completed_in_the_bill_page() {
        openChat()
        drafts("要补充金额", """{"type":"EXPENSE","category_id":${category("food")},"note":"合成咖啡"}""")
        send("买了杯咖啡")
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithTag("ai-draft-flag-amount").assertExists()
        // Waiting drafts put a dot on the way back in.
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("ai-chat-entry-dot")
        openChat()
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithTag("ai-draft-d1-confirm").assertTextEquals("补充金额").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-note").assertTextContains("合成咖啡")
        listOf("D1", "D8").forEach { rule.onNodeWithTag("bill-key-$it").performClick() }
        rule.onNodeWithTag("bill-confirm").performClick()
        // Saved from the bill page, the card follows when the chat comes back.
        waitTag("ai-chat")
        waitTag("ai-draft-d1-confirmed")
        val bill = runBlocking { graph.bills!!.observeSearch("合成咖啡").first().single() }
        assertEquals(1_800L, bill.amountMinor)
        rule.onNodeWithTag("ai-chat-entry-dot").assertDoesNotExist()
    }

    @Test fun errors_offer_a_retry_and_recording_by_hand() {
        openChat()
        ScriptedAiTransport.reply { AiHttpResponse(401, "") }
        send("打车 32")
        waitTag("ai-chat-error-unauthorized")
        drafts("", """{"type":"EXPENSE","amount":"32","category_id":${category("transport")}}""")
        rule.onNodeWithTag("ai-chat-retry").performClick()
        waitTag("ai-draft-d1-pending")
        gone("ai-chat-error-unauthorized")

        // A reply without drafts offers the bill page, with the words in the note.
        ScriptedAiTransport.reply { AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"想记哪一笔？"}}]}""") }
        send("合成闲聊")
        rule.waitUntil(8_000) { rule.onAllNodes(tagStarting("ai-chat-manual-")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(tagStarting("ai-chat-manual-")).onLast().performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-note").assertTextContains("合成闲聊")
    }

    @Test fun another_currency_shows_what_the_account_moves_and_asks_for_a_missing_rate() {
        runBlocking {
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(dev.valnook.domain.repository.SaveFinancialSettings(settings.revision,
                dev.valnook.domain.model.Currency.of("CNY"), emptyList()))
        }
        openChat()
        drafts("", """{"type":"EXPENSE","amount":"10","currency":"USD","category_id":${category("food")},"account_id":$cashId}""")
        send("合成早餐 10 美元")
        waitTag("ai-draft-d1-no-rate")
        rule.onNodeWithTag("ai-draft-d1-confirm").assertTextEquals("填写汇率")
        // The way to the rates; with a rate set there, the card shows what the account moves.
        rule.onNodeWithTag("ai-draft-d1-rates").performClick()
        gone("ai-chat")
        runBlocking {
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(dev.valnook.domain.repository.SaveFinancialSettings(settings.revision,
                dev.valnook.domain.model.Currency.of("CNY"), listOf(dev.valnook.domain.model.FxRate(
                    dev.valnook.domain.model.Currency.of("USD"), dev.valnook.domain.model.Currency.of("CNY"), java.math.BigDecimal("7.2")))))
        }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("ai-draft-d1-converted")
        rule.onNodeWithTag("ai-draft-d1-converted").assertTextEquals("扣款 ≈ 72.00 CNY")
        rule.onNodeWithTag("ai-draft-d1-confirm").assertTextEquals("确认").performClick()
        waitTag("ai-draft-d1-confirmed")
        assertEquals(100_000L - 7_200L, balance())
    }

    @Test fun the_button_shows_only_while_bookkeeping_by_chat_is_usable() = runBlocking {
        waitTag("ai-chat-entry")
        val ai = graph.ai!!
        ai.saveFeatures(billChat = false, shareText = true, shareImage = false, thinking = false, chat = false, sendHabits = false)
        gone("ai-chat-entry")
        ai.saveFeatures(billChat = true, shareText = true, shareImage = false, thinking = false, chat = false, sendHabits = false)
        waitTag("ai-chat-entry")
        // A changed connection voids its checks, and with them the entry.
        ai.saveConnection(AiConnection(null, "https://ai.example.test", "another-model"), null)
        gone("ai-chat-entry")
    }
}
