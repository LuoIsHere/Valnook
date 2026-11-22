package dev.valnook.app

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
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveBill
import dev.valnook.domain.repository.SaveNavigationConfiguration
import dev.valnook.feature.ai.AiNotices
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/** Questions about bills in the chat, against a scripted service and synthetic bills; nothing reaches the network. */
@HiltAndroidTest
class AiQueryChatUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val graph get() = sessions.session.value.graph
    /** The app's own month: tests run on a fixed clock, not the device's date. */
    private lateinit var month: YearMonth

    @Before fun seed() {
        hilt.inject()
        month = YearMonth.now(graph.clock)
        ScriptedAiTransport.reset()
        AiNotices.clear(InstrumentationRegistry.getInstrumentation().targetContext)
        runBlocking {
            // Amounts are counted in the base currency.
            val base = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(dev.valnook.domain.repository.SaveFinancialSettings(base.revision,
                dev.valnook.domain.model.Currency.of("CNY"), emptyList()))
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成银行", "",
                listOf(CashBalanceChange("CNY", 100_000, null, name = "合成储蓄卡")))).id
            val cash = db.cash().accountIds(account).single()
            val at = month.atDay(1).atTime(12, 0).atZone(graph.clock.zone).toInstant().toEpochMilli()
            graph.commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE, at, 3_500,
                "CNY", 0, cash, 3_500, categoryId = category("food"), note = "合成午饭")))
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

    private fun category(key: String) = db.openHelper.readableDatabase
        .query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }

    private fun tagStarting(prefix: String) = SemanticsMatcher("tag starts with $prefix") {
        it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
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

    @Test fun an_answer_shows_its_lookups_and_opens_the_month_in_statistics() {
        runBlocking { graph.ai!!.saveQuery(true) }
        openChat()
        rule.onNodeWithText("这个月餐饮花了多少").assertExists()
        val arguments = """{"bill_type":"EXPENSE","from":"${month.atDay(1)}","to":"${month.atEndOfMonth()}","group_by":"CATEGORY"}"""
        ScriptedAiTransport.reply {
            AiHttpResponse(200, """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":"",
                "tool_calls":[{"id":"q1","type":"function","function":{"name":"query_summary","arguments":${JsonPrimitive(arguments)}}}]}}],
                "usage":{"prompt_tokens":900,"completion_tokens":40,"total_tokens":940}}""")
        }
        // The answer waits until the page has shown what it looked up meanwhile.
        val answer = kotlinx.coroutines.CompletableDeferred<Unit>()
        ScriptedAiTransport.reply {
            answer.await()
            AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"这个月餐饮 35.00。"}}],
                "usage":{"prompt_tokens":1000,"completion_tokens":20,"total_tokens":1020}}""")
        }
        send("这个月餐饮花了多少")
        waitTag("ai-chat-querying")
        rule.onNodeWithTag("ai-chat-querying").assertTextContains("正在查询：", substring = true)
            .assertTextContains("支出 · 按分类", substring = true)
        answer.complete(Unit)
        rule.waitUntil(10_000) { rule.onAllNodes(tagStarting("ai-chat-queries-")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ai-chat-querying").assertDoesNotExist()
        // No bill was asked for, so there is nothing to record by hand.
        rule.onAllNodes(tagStarting("ai-chat-manual-")).assertCountEquals(0)
        val process = rule.onNode(tagStarting("ai-chat-queries-"))
        process.assertTextContains("查询过程 · 1 次", substring = true).performClick()
        // The lines are read out with the folded part, which is one control.
        rule.waitUntil(5_000) { rule.onAllNodes(tagStarting("ai-chat-query-"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(tagStarting("ai-chat-query-"), useUnmergedTree = true).assertTextContains("按分类", substring = true)
            .assertTextContains("1 行", substring = true)
        rule.onNode(tagStarting("ai-chat-open-stats-")).performScrollTo().performClick()
        waitTag("bill-stats-expense")
        rule.onNodeWithTag("bill-stats-total").assertTextContains("35.00", substring = true)
    }

    @Test fun with_questions_off_a_reply_naming_the_switch_leads_to_it() {
        openChat()
        rule.onNodeWithText("这个月餐饮花了多少").assertDoesNotExist()
        ScriptedAiTransport.reply {
            AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"打开设置里的“对话查询”后可以回答这类问题。"}}],
                "usage":{"prompt_tokens":800,"completion_tokens":20,"total_tokens":820}}""")
        }
        send("上个月花了多少")
        rule.waitUntil(8_000) { rule.onAllNodes(tagStarting("ai-chat-turn-on-query-")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(tagStarting("ai-chat-manual-")).assertCountEquals(0)
        rule.onNode(tagStarting("ai-chat-turn-on-query-")).performClick()
        waitTag("ai-settings")
        rule.onNodeWithTag("ai-settings").performScrollToNode(hasTestTag("ai-feature-query"))
        rule.onNodeWithTag("ai-feature-query").performClick()
        rule.waitUntil(5_000) { runBlocking { graph.ai!!.observeSettings().first().query } }
        assertTrue(runBlocking { graph.ai!!.observeFeatures().first().query })
    }
}
