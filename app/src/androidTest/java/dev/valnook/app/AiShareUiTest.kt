package dev.valnook.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.share.ShareActivity
import dev.valnook.data.ai.AiHttpResponse
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.ai.*
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
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
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Sharing text and pictures into Valnook, with AI and without; synthetic data, a scripted service. */
@HiltAndroidTest
class AiShareUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createEmptyComposeRule()
    @Inject lateinit var sessions: AppSessionManager
    @Inject lateinit var db: ValnookDatabase
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = sessions.session.value.graph
    private var cashId = 0L
    private var scenario: ActivityScenario<ShareActivity>? = null

    @Before fun seed() {
        hilt.inject()
        ScriptedAiTransport.reset()
        AiNotices.clear(context)
        runBlocking {
            val account = graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "合成银行", "",
                listOf(CashBalanceChange("CNY", 100_000, null, name = "合成储蓄卡")))).id
            cashId = db.cash().accountIds(account).single()
            // Set up as after onboarding: a bill on no account is in the base currency.
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(dev.valnook.domain.repository.SaveFinancialSettings(settings.revision,
                dev.valnook.domain.model.Currency.of("CNY"), emptyList()))
        }
    }

    @After fun clean() {
        scenario?.close()
        ScriptedAiTransport.reset()
    }

    private fun enableAi(pictures: Boolean, shareText: Boolean = true) = runBlocking {
        val ai = graph.ai!!
        ai.saveConnection(AiConnection(null, "https://ai.example.test", "synthetic-model"), "synthetic-key")
        val fingerprint = ai.observeSettings().first().connection.fingerprint
        TestGraphs.real.aiChecks.save(AiCheckReport(fingerprint, 0, buildMap {
            put(AiCheckItem.CONNECT, AiCheck(AiCheckState.PASSED)); put(AiCheckItem.TOOLS, AiCheck(AiCheckState.PASSED))
            if (pictures) put(AiCheckItem.IMAGE, AiCheck(AiCheckState.PASSED))
        }))
        ai.setEnabled(true)
        ai.saveFeatures(billChat = true, shareText = shareText, shareImage = pictures, thinking = false, chat = false, sendHabits = false)
    }

    private fun category(key: String) = db.openHelper.readableDatabase
        .query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }

    private fun waitTag(tag: String, timeout: Long = 8_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun closed() = scenario!!.state == Lifecycle.State.DESTROYED

    private fun share(text: String? = null, pictures: List<android.net.Uri> = emptyList()) {
        val intent = Intent(context, ShareActivity::class.java).apply {
            when {
                pictures.size > 1 -> { action = Intent.ACTION_SEND_MULTIPLE; type = "image/png"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(pictures)) }
                pictures.size == 1 -> { action = Intent.ACTION_SEND; type = "image/png"; putExtra(Intent.EXTRA_STREAM, pictures.single()) }
                else -> { action = Intent.ACTION_SEND; type = "text/plain" }
            }
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scenario = ActivityScenario.launch(intent)
    }

    /** A small synthetic picture, shared through the app's own file provider as another app's would be. */
    private fun picture(color: Int): android.net.Uri {
        val dir = File(context.cacheDir, "bill_camera").apply { mkdirs() }
        val file = File(dir, "share-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.billcamera", file)
    }

    private fun drafts(vararg bills: String) {
        val arguments = """{"message":"识别到一笔","bills":[${bills.joinToString(",")}]}"""
        ScriptedAiTransport.reply {
            AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"","tool_calls":[{"id":"s1",
                "type":"function","function":{"name":"create_bill_drafts","arguments":${JsonPrimitive(arguments)}}}]}}],
                "usage":{"prompt_tokens":800,"completion_tokens":40,"total_tokens":840}}""")
        }
    }

    @Test fun shared_text_is_read_once_and_the_page_closes_when_done() {
        enableAi(pictures = false)
        drafts("""{"type":"EXPENSE","amount":"38.5","currency":"CNY","category_id":${category("food")},"account_id":$cashId,"note":"合成外卖"}""")
        share("合成外卖 实付 38.50 元")
        waitTag("ai-draft-d1-pending")
        // The currency read off the share is shown by name and code, and not flagged.
        rule.onNodeWithTag("ai-draft-details").assertTextContains("人民币 CNY", substring = true)
        rule.onNodeWithTag("ai-draft-flag-currency").assertDoesNotExist()
        rule.onNodeWithTag("ai-share-host").assertTextEquals("将发送到 ai.example.test")
        val request = ScriptedAiTransport.requests.single()!!
        assertEquals("create_bill_drafts", request["tool_choice"]!!.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue(request["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content.endsWith("合成外卖 实付 38.50 元"))
        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        rule.waitUntil(8_000) { closed() }
        assertEquals(100_000L - 3_850L, runBlocking { db.cash().cashAccount(cashId)!!.balance_minor })
        // A share is not a conversation; its tokens still count.
        runBlocking {
            assertTrue(graph.assistant!!.observeConversations().first().isEmpty())
            assertEquals(840L, graph.assistant!!.observeUsage().first().tokens)
        }
    }

    @Test fun without_ai_for_text_the_bill_page_opens_with_it_in_the_note() {
        enableAi(pictures = false, shareText = false)
        share("合成停车费 15")
        waitTag("bill-editor")
        rule.onNodeWithTag("bill-note").assertTextContains("合成停车费 15")
        listOf("D1", "D5").forEach { rule.onNodeWithTag("bill-key-$it").performClick() }
        rule.onNodeWithTag("bill-category-transport").performClick()
        rule.onNodeWithTag("bill-confirm").performClick()
        rule.waitUntil(8_000) { closed() }
        assertTrue(ScriptedAiTransport.requests.isEmpty())
        val bill = runBlocking { graph.bills!!.observeSearch("合成停车费").first().single() }
        assertEquals(1_500L, bill.amountMinor)
    }

    @Test fun without_ai_for_pictures_they_go_onto_the_bill() {
        enableAi(pictures = false)
        share(pictures = listOf(picture(Color.RED), picture(Color.BLUE)))
        waitTag("bill-editor")
        waitTag("bill-editor-image-1")
        assertTrue(ScriptedAiTransport.requests.isEmpty())
    }

    @Test fun shared_pictures_are_read_and_kept_with_the_bill() {
        enableAi(pictures = true)
        drafts("""{"type":"EXPENSE","amount":"20","category_id":${category("food")},"note":"合成小票"}""")
        share(pictures = listOf(picture(Color.GREEN)))
        waitTag("ai-draft-d1-pending")
        val parts = ScriptedAiTransport.requests.single()!!["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
        assertEquals(listOf("text", "image_url"), parts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
        rule.onNodeWithTag("ai-draft-d1-keep-images").assertIsOn()
        // No currency read off the picture: the account's is used and the card says so.
        rule.onNodeWithTag("ai-draft-flag-currency").assertExists()
        rule.onNodeWithTag("ai-draft-d1-confirm").performClick()
        rule.waitUntil(8_000) { closed() }
        val bill = runBlocking { graph.bills!!.observeSearch("合成小票").first().single() }
        assertEquals(1, runBlocking { graph.bills!!.observeImages(bill.id).first().size })
    }
}
