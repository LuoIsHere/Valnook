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

/** AI bookkeeping against a scripted service, with synthetic accounts only; nothing reaches the network. */
@HiltAndroidTest
class AiChatUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = sessions.session.value.graph
    private var cashId = 0L
    private var calls = 0
    private var keyboard: ScreenKeyboard? = null

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

    @After fun clean() {
        keyboard?.restore()
        ScriptedAiTransport.reset()
    }

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

    /** The keyboard shortens the list from below; the newest message moves up with it instead of going under. */
    @Test fun opening_the_keyboard_keeps_the_newest_message_in_view() {
        openChat()
        // Four turns, each with a card: taller than the screen, so the newest is at the bottom.
        repeat(4) { turn ->
            drafts("合成回复 $turn", """{"type":"EXPENSE","amount":"1$turn","currency":"CNY","category_id":${category("food")},
                "account_id":$cashId,"note":"合成午餐 $turn"}""")
            send("合成午餐 1$turn 元")
            rule.waitUntil(8_000) { rule.onAllNodes(hasText("合成午餐 $turn"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        }
        // Back up through the history, then down to the end by hand, as a reader would.
        val messages = rule.onNodeWithTag("ai-chat-messages")
        repeat(2) { messages.performTouchInput { swipeDown() } }
        repeat(4) { messages.performTouchInput { swipeUp() } }
        val newest = rule.onNode(hasText("合成午餐 3"), useUnmergedTree = true)
        rule.waitForIdle()
        newest.assertIsDisplayed()
        val screen = ScreenKeyboard { rule.activity.window.decorView }.also { keyboard = it; it.prepare() }
        rule.onNode(hasSetTextAction() and hasTestTag("ai-chat-input")).performClick()
        screen.show(rule)
        screen.assertAbove(rule, "chat-newest-message", newest)
    }

    @Test fun a_rate_the_user_gives_is_used_for_that_bill_and_checked_against_the_app_rate() {
        runBlocking {
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(dev.valnook.domain.repository.SaveFinancialSettings(settings.revision,
                dev.valnook.domain.model.Currency.of("CNY"), listOf(dev.valnook.domain.model.FxRate(
                    dev.valnook.domain.model.Currency.of("USD"), dev.valnook.domain.model.Currency.of("CNY"), java.math.BigDecimal("7.2")))))
        }
        openChat()
        drafts("记好了", """{"type":"EXPENSE","amount":"3","currency":"USD","category_id":${category("food")},
            "account_id":$cashId,"rate":"7.1","note":"合成可乐"}""", """{"type":"EXPENSE","amount":"2","currency":"USD",
            "category_id":${category("food")},"account_id":$cashId,"rate":"0.14","note":"合成矿泉水"}""")
        send("可乐 3 美元，汇率 7.1；矿泉水 2 美元，汇率 0.14")
        waitTag("ai-draft-d2-pending")
        // The cards settle in as they arrive: wait for the line itself.
        waitTag("ai-draft-d1-converted")
        rule.onNodeWithTag("ai-draft-d1-converted").assertTextEquals("扣款 ≈ 21.30 CNY（按提供的汇率 7.1）")
        rule.onNodeWithTag("ai-draft-d1-rate-far").assertDoesNotExist()
        // Far from the app's 7.2, perhaps said the other way round: the card asks to check it.
        rule.onNodeWithTag("ai-draft-d2-rate-far").assertExists()
        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        waitTag("ai-draft-d1-confirmed")
        assertEquals(100_000L - 2_130L, balance())
        db.openHelper.readableDatabase.query("SELECT rate_source, rate FROM bill_entries WHERE note=?", arrayOf("合成可乐")).use {
            assertTrue(it.moveToFirst())
            assertEquals("ONE_TIME", it.getString(0))
            assertEquals(0, java.math.BigDecimal(it.getString(1)).compareTo(java.math.BigDecimal("7.1")))
        }
    }

    @Test fun the_quick_start_opens_from_ai_settings_and_enters_its_example() {
        rule.onNodeWithTag("nav-settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-ai"))
        rule.onNodeWithTag("settings-ai").performClick()
        waitTag("ai-settings")
        rule.onNodeWithTag("ai-guide-open").performClick()
        waitTag("ai-guide")
        rule.onNodeWithTag("ai-guide-page-0").assertExists()
        repeat(7) { rule.onNodeWithTag("ai-guide-next").performClick(); rule.waitForIdle() }
        rule.onNodeWithTag("ai-guide-step").assertTextEquals("8 / 8")
        rule.onNodeWithTag("ai-guide-fill").performScrollTo().performClick()
        // Back on the settings, which ask before replacing the connection there.
        waitTag("ai-guide-replace")
        rule.onNodeWithTag("ai-guide-replace-confirm").performClick()
        rule.onNodeWithTag("ai-base-url").assertTextContains("https://api.deepseek.com")
        rule.onNodeWithTag("ai-guide").assertDoesNotExist()
        // Entered only: the saved connection is unchanged until the user saves.
        assertEquals("https://ai.example.test", runBlocking { graph.ai!!.observeSettings().first().connection.baseUrl })
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
        // The AI's bubble never comes before the user's message: by the time it shows, the message is there.
        rule.onNode(hasText("合成午饭 25") and (hasAnyAncestor(tagStarting("ai-chat-user-")) or hasAnyAncestor(hasTestTag("ai-chat-pending")))).assertExists()
        // Stored, it stays one message.
        rule.waitUntil(8_000) { rule.onAllNodes(hasText("合成午饭 25") and hasAnyAncestor(tagStarting("ai-chat-user-"))).fetchSemanticsNodes().size == 1 }
        rule.onNodeWithTag("ai-chat-pending").assertDoesNotExist()
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

    @Test fun the_thinking_switch_waits_for_its_check_and_goes_with_this_conversation_only() {
        openChat()
        waitTag("ai-chat-thinking")
        // Not checked: greyed out, and a tap leaves it off.
        rule.onNodeWithTag("ai-chat-thinking").assertIsOff().performClick().assertIsOff()
        runBlocking {
            val ai = graph.ai!!
            ai.saveConnection(AiConnection(null, "https://ai.example.test", "synthetic-model", "", """{"thinking":"off"}""",
                """{"thinking":"on"}"""), "synthetic-key")
            val fingerprint = ai.observeSettings().first().connection.fingerprint
            TestGraphs.real.aiChecks.save(AiCheckReport(fingerprint, 0, AiCheckItem.entries.associateWith { AiCheck(AiCheckState.PASSED) }))
        }
        // Once the check is in, the switch can be used, and one tap turns it on (tapping in a loop could turn it back off).
        rule.waitUntil(8_000) { runCatching { rule.onNodeWithTag("ai-chat-thinking").assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag("ai-chat-thinking").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithTag("ai-chat-thinking").assertIsOn() }.isSuccess }
        drafts("One bill", """{"type":"EXPENSE","amount":"12"}""")
        send("Lunch 12")
        waitTag("ai-draft-d1-pending")
        val request = ScriptedAiTransport.requests.last()!!
        assertEquals("on", request["thinking"]!!.jsonPrimitive.content)
        assertEquals(AiRules.MAX_TOKENS_THINKING, request["max_tokens"]!!.jsonPrimitive.content.toInt())
        rule.onNodeWithTag("ai-chat-thinking").assertIsOn()
        // A new conversation starts from the setting, which is still off.
        rule.onNodeWithTag("ai-chat-new").performClick()
        rule.waitUntil(8_000) { runCatching { rule.onNodeWithTag("ai-chat-thinking").assertIsOff() }.isSuccess }
        runBlocking { assertTrue(!graph.ai!!.observeSettings().first().thinking) }
    }

    @Test fun drafts_waiting_in_another_conversation_can_be_found_from_a_new_one() {
        openChat()
        drafts("One bill", """{"type":"EXPENSE","amount":"12"}""")
        send("Lunch 12")
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithTag("ai-chat-pending-elsewhere").assertDoesNotExist()
        rule.onNodeWithTag("ai-chat-history-dot", useUnmergedTree = true).assertDoesNotExist()
        // A new conversation points back to it: on the empty page and on the history button.
        rule.onNodeWithTag("ai-chat-new").performClick()
        waitTag("ai-chat-pending-elsewhere")
        rule.onNodeWithTag("ai-chat-history-dot", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("ai-chat-examples").assertExists()
        rule.onNodeWithTag("ai-chat-pending-elsewhere").performClick()
        waitTag("ai-chat-history")
        rule.assertReachesScreenBottom("ai-chat-history", hasScrollAction() and hasAnyAncestor(hasTestTag("ai-chat-history")))
        rule.onNode(tagStarting("ai-chat-history-pending-"), useUnmergedTree = true).assertTextContains("1", substring = true)
        val row = rule.onNode(tagStarting("ai-chat-history-pending-"), useUnmergedTree = true).fetchSemanticsNode()
            .config[SemanticsProperties.TestTag].removePrefix("ai-chat-history-pending-")
        rule.onNodeWithTag("ai-chat-history-$row").performClick()
        waitTag("ai-draft-d1-pending")
        rule.waitUntil(8_000) { rule.onAllNodesWithTag("ai-chat-history-dot", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }

    /** A second synthetic sub-account, to move bills to. */
    private fun secondAccount(): Long = runBlocking {
        val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成钱包", "",
            listOf(CashBalanceChange("CNY", 50_000, null, name = "合成零钱")))).id
        db.cash().accountIds(account).single()
    }

    private fun balanceOf(id: Long) = runBlocking { db.cash().cashAccount(id)!!.balance_minor }

    @Test fun a_draft_changed_in_the_bill_page_shows_what_was_saved() {
        val other = secondAccount()
        openChat()
        drafts("记好了", """{"type":"EXPENSE","amount":"30","category_id":${category("food")},"account_id":$cashId,"note":"合成晚饭"}""")
        send("晚饭 30")
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithTag("ai-draft-details").assertTextContains("合成银行 · 合成储蓄卡", substring = true)
        rule.onNodeWithTag("ai-draft-d1-edit").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-account").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-account-$other").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        // Back in the chat the card shows the bill as saved, on the other account.
        waitTag("ai-chat")
        waitTag("ai-draft-d1-confirmed")
        rule.waitUntil(8_000) {
            rule.onAllNodes(hasTestTag("ai-draft-details") and hasText("合成钱包 · 合成零钱", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(100_000L, balance())
        assertEquals(50_000L - 3_000, balanceOf(other))
    }

    @Test fun a_saved_card_is_changed_again_in_the_bill_page_and_follows_its_bill() {
        val other = secondAccount()
        openChat()
        drafts("记好了", """{"type":"EXPENSE","amount":"12","category_id":${category("food")},"account_id":$cashId,"note":"合成早饭"}""")
        send("早饭 12")
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        waitTag("ai-draft-d1-confirmed")
        assertEquals(100_000L - 1_200, balance())
        // Saved straight from the card, it can still be changed in the bill page.
        rule.onNodeWithTag("ai-draft-d1-edit-bill").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-account").assertTextEquals("合成银行 · 合成储蓄卡").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-account-$other").performClick()
        rule.onNodeWithTag("bill-note").performTextReplacement("合成早餐加蛋")
        rule.onNodeWithTag("bill-amount").performClick()
        repeat(5) { rule.onNodeWithTag("bill-key-BACKSPACE").performClick() }
        listOf("D1", "D5").forEach { rule.onNodeWithTag("bill-key-$it").performClick() }
        rule.onNodeWithTag("bill-category-transport").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        waitTag("ai-chat")
        rule.waitUntil(8_000) {
            rule.onAllNodes(hasTestTag("ai-draft-details") and hasText("合成钱包 · 合成零钱", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        waitTag("ai-draft-d1-confirmed")
        // Everything the bill page changed shows on the card, not only the account.
        rule.onNodeWithText("交通").assertExists()
        rule.onNodeWithText("15.00 CNY", substring = true).assertExists()
        rule.onNodeWithText("合成早餐加蛋").assertExists()
        rule.onNodeWithText("合成早饭").assertDoesNotExist()
        assertEquals(100_000L, balance())
        assertEquals(50_000L - 1_500, balanceOf(other))
        // Taken back, the card waits as it was last saved: on the other account, with the new figures.
        rule.onNodeWithTag("ai-draft-d1-undo").performClick()
        waitTag("ai-draft-d1-pending")
        assertEquals(50_000L, balanceOf(other))
        rule.onNodeWithTag("ai-draft-details").assertTextContains("合成钱包 · 合成零钱", substring = true)
        rule.onNodeWithText("15.00 CNY", substring = true).assertExists()
        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        waitTag("ai-draft-d1-confirmed")
        assertEquals(50_000L - 1_500, balanceOf(other))

        // Deleted elsewhere, the bill has nothing left to change: back on the page the card no longer offers it.
        runBlocking {
            val bill = graph.bills!!.observeSearch("合成早餐加蛋").first().single()
            graph.commands.execute(dev.valnook.domain.repository.DeleteBill(UUID.randomUUID().toString(), bill.id, bill.revision))
        }
        rule.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        openChat()
        waitTag("ai-draft-d1-confirmed")
        gone("ai-draft-d1-edit-bill")
        rule.onNodeWithTag("ai-draft-d1-undo").assertExists()
    }

    @Test fun a_card_taken_back_on_another_currency_works_out_its_conversion_again() {
        runBlocking {
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(dev.valnook.domain.repository.SaveFinancialSettings(settings.revision,
                dev.valnook.domain.model.Currency.of("CNY"), listOf(dev.valnook.domain.model.FxRate(
                    dev.valnook.domain.model.Currency.of("USD"), dev.valnook.domain.model.Currency.of("CNY"), java.math.BigDecimal("7.2")))))
        }
        val dollars = runBlocking {
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成外币卡", "",
                listOf(CashBalanceChange("USD", 50_000, null, name = "合成美元")))).id
            db.cash().accountIds(account).single()
        }
        openChat()
        drafts("记好了", """{"type":"EXPENSE","amount":"10","currency":"USD","category_id":${category("food")},
            "account_id":$cashId,"note":"合成早午饭"}""")
        send("早午饭 10 美元")
        waitTag("ai-draft-d1-converted")
        rule.onNodeWithTag("ai-draft-d1-converted").assertTextEquals("扣款 ≈ 72.00 CNY")
        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        waitTag("ai-draft-d1-confirmed")
        // Moved to the dollar account in the bill page, then taken back: the card no longer crosses currencies.
        rule.onNodeWithTag("ai-draft-d1-edit-bill").performClick()
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-account").performClick()
        waitTag("bill-account-picker")
        rule.onNodeWithTag("bill-account-$dollars").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        waitTag("ai-chat")
        rule.waitUntil(8_000) {
            rule.onAllNodes(hasTestTag("ai-draft-details") and hasText("合成外币卡 · 合成美元", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(50_000L - 1_000, balanceOf(dollars))
        rule.onNodeWithTag("ai-draft-d1-undo").performClick()
        waitTag("ai-draft-d1-pending")
        rule.onNodeWithTag("ai-draft-details").assertTextContains("合成外币卡 · 合成美元", substring = true)
        gone("ai-draft-d1-converted")
        rule.onNodeWithTag("ai-draft-d1-confirm").assertTextEquals("确认")
    }
}
