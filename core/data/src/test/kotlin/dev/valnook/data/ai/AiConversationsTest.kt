package dev.valnook.data.ai

import dev.valnook.domain.ai.AiBillDraft
import dev.valnook.domain.ai.AiDraftFlag
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillType
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** What earlier rounds send, and how drafts and pictures are kept. */
class AiConversationsTest {
    private var nextId = 1L
    private fun user(text: String) = message("USER", text, null)
    private fun reply(raw: String) = message("ASSISTANT", "", raw)
    private fun error() = message("ERROR", "", null)
    private fun message(kind: String, text: String, raw: String?) = AiMessageEntity(nextId++, 1, kind, text, raw, null, null,
        null, null, null, null, 1, null, false, "[]", 0)
    private fun draft(message: Long, label: String, state: String) =
        AiDraftEntity(0, 1, message, label, "{}", state, null, "op", "[]", true)

    @Test fun replies_go_back_unchanged_with_a_result_for_every_tool_call() {
        val ask = user("Breakfast 8, lunch 68")
        val raw = """{"role":"assistant","content":"","reasoning_content":"two bills","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"create_bill_drafts","arguments":"{}"}},
            {"id":"c2","type":"function","function":{"name":"create_bill_drafts","arguments":"{}"}}]}"""
        val answer = reply(raw)
        val failed = user("Coffee")
        val history = AiConversations.history(listOf(ask, answer, failed, error()),
            listOf(draft(answer.id, "d1", "CONFIRMED"), draft(answer.id, "d2", "PENDING")), mapOf(ask.id to 2))
        assertEquals(listOf("user", "assistant", "tool", "tool", "user"), history.map { it["role"]!!.jsonPrimitive.content })
        // The reply exactly as received, thinking included.
        assertEquals(AiJson.format.parseToJsonElement(raw), history[1])
        assertEquals("c1", history[2]["tool_call_id"]!!.jsonPrimitive.content)
        val status = history[2]["content"]!!.jsonPrimitive.content
        assertTrue(status, status.contains("d1 saved by the user"))
        assertTrue(status, status.contains("d2 waiting for the user to confirm"))
        assertEquals("Done.", history[3]["content"]!!.jsonPrimitive.content)
        // Pictures are mentioned, not sent again; a message that failed is still there, its error is not.
        assertEquals("Breakfast 8, lunch 68\n[2 picture(s) sent earlier, not repeated]", history[0]["content"]!!.jsonPrimitive.content)
        assertEquals("Coffee", history[4]["content"]!!.jsonPrimitive.content)
    }

    @Test fun only_the_last_six_rounds_are_sent() {
        val messages = (1..8).flatMap { listOf(user("m$it"), reply("""{"role":"assistant","content":"r$it"}""")) }
        val history = AiConversations.history(messages, emptyList(), emptyMap())
        assertEquals(12, history.size)
        assertEquals("m3", history.first()["content"]!!.jsonPrimitive.content)
        assertTrue(AiConversations.history(emptyList(), emptyList(), emptyMap()).isEmpty())
    }

    @Test fun pictures_go_one_per_draft_when_the_counts_match_otherwise_all_with_the_first() {
        val a = BillImage("a".repeat(64), 1, 1, 1)
        val b = BillImage("b".repeat(64), 1, 1, 1)
        assertEquals(listOf(listOf(a), listOf(b)), AiConversations.assignPictures(listOf(a, b), 2))
        assertEquals(listOf(listOf(a, b), emptyList(), emptyList()), AiConversations.assignPictures(listOf(a, b), 3))
        assertEquals(listOf(listOf(a, b)), AiConversations.assignPictures(listOf(a, b), 1))
        assertTrue(AiConversations.assignPictures(listOf(a), 0).isEmpty())
    }

    @Test fun pictures_are_sent_as_data_urls_after_the_words() {
        val message = AiConversations.userMessage("Lunch", listOf(byteArrayOf(1, 2, 3)))
        val parts = message["content"]!!.jsonArray
        assertEquals("Lunch", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,AQID", parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("Lunch", AiConversations.userMessage("Lunch", emptyList())["content"]!!.jsonPrimitive.content)
    }

    @Test fun labels_titles_and_reply_text() {
        assertEquals("d1", AiConversations.nextLabel(emptyList()))
        assertEquals("d5", AiConversations.nextLabel(listOf(draft(1, "d3", "PENDING"), draft(1, "x", "PENDING")), taken = 1))
        assertEquals("Breakfast", AiConversations.title("\n Breakfast \nmore", 0, true))
        assertEquals("一二三四五六七八九十一二三四五六…", AiConversations.title("一二三四五六七八九十一二三四五六七", 0, false))
        assertEquals("图片", AiConversations.title(" ", 2, false))
        assertEquals("Saved two\nAnything else?", AiConversations.replyText(" Saved two ", listOf("Saved two", "Anything else?", "")))
    }

    @Test fun drafts_and_pictures_survive_being_stored() {
        val draft = AiBillDraft(BillType.TRANSFER, 1200, "USD", 0, null, null, null, 7, LocalDateTime.of(2026, 11, 1, 9, 0),
            "Rent", "Landlord", false, setOf(AiDraftFlag.TIME, AiDraftFlag.ACCOUNT))
        assertEquals(draft, AiConversations.decodeDraft(AiConversations.encodeDraft(draft)))
        val missing = draft.copy(type = BillType.EXPENSE, amountMinor = null, categoryId = 3, subCategoryId = 31, flags = emptySet())
        assertEquals(missing, AiConversations.decodeDraft(AiConversations.encodeDraft(missing)))
        val received = draft.copy(amountMinor = null, toAmountMinor = 1000)
        assertEquals(received, AiConversations.decodeDraft(AiConversations.encodeDraft(received)))
        val rated = missing.copy(amountMinor = 300, rate = java.math.BigDecimal("0.9"))
        assertEquals(rated, AiConversations.decodeDraft(AiConversations.encodeDraft(rated)))
        val charged = missing.copy(amountMinor = 300, accountAmountMinor = 243)
        assertEquals(charged, AiConversations.decodeDraft(AiConversations.encodeDraft(charged)))
        assertNull(AiConversations.decodeDraft("{"))
        val images = listOf(BillImage("c".repeat(64), 1000, 30, 40))
        assertEquals(images, AiConversations.decodeImages(AiConversations.encodeImages(images)))
        assertEquals(listOf(4L, 9L), AiConversations.decodeIds(AiConversations.encodeIds(listOf(4, 9))))
    }
}
