package dev.valnook.data.ai

import dev.valnook.domain.ai.AiAccountChoice
import dev.valnook.domain.ai.AiDraftFlag
import dev.valnook.domain.ai.AiSettings
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

/** A bad field is dropped and filled, never the whole bill; only what was sent can come back. */
class AiDraftReaderTest {
    private fun category(id: Long, parent: Long?, kind: BillCategoryKind = BillCategoryKind.EXPENSE, key: String? = null,
        hidden: Boolean = false, last: Long? = null) =
        BillCategory(id, kind, parent, key, "c$id", "icon", "grey", id, hidden, last, 1)

    private val categories = listOf(
        category(1, null, key = "food"), category(11, 1, last = 7), category(12, 1),
        category(2, null, key = "other"), category(21, 2, key = "other.uncategorized"),
        category(3, null, hidden = true), category(31, 3),
        category(5, null, BillCategoryKind.INCOME, key = "income.other"), category(6, null, BillCategoryKind.INCOME, last = 8),
    )
    private val all = listOf(AiAccountChoice(7, "Card", "CNY", true), AiAccountChoice(8, "Wallet", "USD", false),
        AiAccountChoice(9, "Yen", "JPY", true))
    private val sent = all.filter { it.favorite }
    private val now = LocalDateTime.of(2026, 11, 14, 9, 30)

    private fun reader(settings: AiSettings = AiSettings()) = AiDraftReader(settings, categories, sent, all, now, "CNY")
    private fun one(bill: String, settings: AiSettings = AiSettings()) = reader(settings).read("""{"bills":[$bill]}""").drafts.single().second

    @Test fun a_full_bill_comes_through_as_written() {
        val call = reader().read("""{"message":"Two bills","bills":[{"draft_id":"d2","type":"EXPENSE","amount":"68.50",
            "currency":"cny","discount":"5","category_id":1,"sub_category_id":12,"account_id":7,
            "occurred_at":"2026-11-13T12:05","note":" Lunch ","counterparty":"Team","reimbursable":true}]}""")
        assertEquals("Two bills", call.message)
        val (label, draft) = call.drafts.single()
        assertEquals("d2", label)
        assertEquals(BillType.EXPENSE, draft.type)
        assertEquals(6850L, draft.amountMinor)
        assertEquals("CNY", draft.currencyCode)
        assertEquals(500L, draft.discountMinor)
        assertEquals(1L to 12L, draft.categoryId to draft.subCategoryId)
        assertEquals(7L, draft.cashAccountId)
        assertEquals(LocalDateTime.of(2026, 11, 13, 12, 5), draft.occurredAt)
        assertEquals("Lunch", draft.note)
        assertEquals("Team", draft.counterparty)
        assertTrue(draft.reimbursable)
        assertEquals(emptySet<AiDraftFlag>(), draft.flags)
    }

    @Test fun categories_must_be_shown_ones_of_the_right_kind() {
        // A subcategory alone brings its parent; a subcategory passed as the category too.
        assertEquals(1L to 11L, one("""{"type":"EXPENSE","amount":"1","sub_category_id":11}""").let { it.categoryId to it.subCategoryId })
        assertEquals(1L to 12L, one("""{"type":"EXPENSE","amount":"1","category_id":12}""").let { it.categoryId to it.subCategoryId })
        // A subcategory under another parent is dropped; the category stays.
        val mixed = one("""{"type":"EXPENSE","amount":"1","category_id":2,"sub_category_id":11}""")
        assertEquals(2L to null, mixed.categoryId to mixed.subCategoryId)
        assertTrue(AiDraftFlag.CATEGORY !in mixed.flags)
        // Hidden, unknown, of the other kind, or none: the default, flagged.
        for (bad in listOf("\"category_id\":3", "\"category_id\":31", "\"category_id\":99", "\"category_id\":5", "")) {
            val draft = one("""{"type":"EXPENSE","amount":"1"${if (bad.isEmpty()) "" else ",$bad"}}""")
            assertEquals(bad, 2L to 21L, draft.categoryId to draft.subCategoryId)
            assertTrue(bad, AiDraftFlag.CATEGORY in draft.flags)
        }
        assertEquals(5L, one("""{"type":"INCOME","amount":"1","category_id":1}""").categoryId)
        // The default from the settings comes first.
        assertEquals(1L to 12L, one("""{"type":"EXPENSE","amount":"1"}""", AiSettings(defaultExpenseCategoryId = 12))
            .let { it.categoryId to it.subCategoryId })
    }

    @Test fun accounts_fall_back_like_the_bill_form_then_to_the_default() {
        // Only sent accounts count: 8 exists but was not sent.
        val unsent = one("""{"type":"EXPENSE","amount":"1","category_id":1,"account_id":8}""")
        assertNull(unsent.cashAccountId)
        assertTrue(AiDraftFlag.ACCOUNT in unsent.flags)
        // A subcategory's own last account, as the form does; not the parent's.
        assertEquals(7L, one("""{"type":"EXPENSE","amount":"1","category_id":1,"sub_category_id":11}""").cashAccountId)
        assertNull(one("""{"type":"EXPENSE","amount":"1","category_id":1,"sub_category_id":12}""").cashAccountId)
        val income = one("""{"type":"INCOME","amount":"1","category_id":6}""")
        assertEquals(8L, income.cashAccountId)
        assertEquals("USD", income.currencyCode)
        assertTrue(AiDraftFlag.ACCOUNT !in income.flags)
        val fallback = one("""{"type":"EXPENSE","amount":"1","category_id":1}""", AiSettings(defaultCashAccountId = 9))
        assertEquals(9L, fallback.cashAccountId)
        assertEquals("JPY", fallback.currencyCode)
        assertTrue(AiDraftFlag.ACCOUNT in fallback.flags)
    }

    @Test fun times_follow_the_rules() {
        assertEquals(LocalDateTime.of(2026, 11, 12, 12, 0), one("""{"type":"EXPENSE","amount":"1","occurred_at":"2026-11-12"}""").occurredAt)
        assertEquals(LocalDateTime.of(2026, 11, 12, 7, 30), one("""{"type":"EXPENSE","amount":"1","occurred_at":"2026-11-12"}""",
            AiSettings(defaultTimeMinutes = 450)).occurredAt)
        assertEquals(LocalDateTime.of(2026, 11, 12, 8, 1), one("""{"type":"EXPENSE","amount":"1","occurred_at":"2026-11-12 08:01:59"}""").occurredAt)
        val none = one("""{"type":"EXPENSE","amount":"1"}""")
        assertEquals(now, none.occurredAt)
        assertTrue(AiDraftFlag.TIME !in none.flags)
        for (bad in listOf("yesterday", "2024-01-01T10:00", "2028-01-01")) {
            val draft = one("""{"type":"EXPENSE","amount":"1","occurred_at":"$bad"}""")
            assertEquals(bad, now, draft.occurredAt)
            assertTrue(bad, AiDraftFlag.TIME in draft.flags)
        }
    }

    @Test fun amounts_are_positive_and_in_the_currency_places() {
        for (bad in listOf("", ",\"amount\":\"0\"", ",\"amount\":\"-3\"", ",\"amount\":\"abc\"")) {
            val draft = one("""{"type":"EXPENSE"$bad}""")
            assertNull(bad, draft.amountMinor)
            assertTrue(bad, AiDraftFlag.AMOUNT in draft.flags)
        }
        assertEquals(1235L, one("""{"type":"EXPENSE","amount":"12.345"}""").amountMinor)
        assertEquals(123456L, one("""{"type":"EXPENSE","amount":"1,234.56"}""").amountMinor)
        assertEquals(1235L, one("""{"type":"EXPENSE","amount":1234.5,"currency":"JPY"}""").amountMinor)
        // An unknown currency follows the account, then the base currency.
        assertEquals("CNY", one("""{"type":"EXPENSE","amount":"1","currency":"XYZ"}""").currencyCode)
        // A discount must be less than the amount.
        assertEquals(0L, one("""{"type":"EXPENSE","amount":"10","discount":"10"}""").discountMinor)
        // Only an expense can be paid back.
        assertFalse(one("""{"type":"INCOME","amount":"10","reimbursable":true}""").reimbursable)
        assertTrue(one("""{"type":"EXPENSE","amount":"10","reimbursable":"true"}""").reimbursable)
    }

    @Test fun transfers_take_the_currency_of_their_account_and_need_one() {
        val transfer = one("""{"type":"TRANSFER","amount":"100","currency":"USD","account_id":9,"to_account_id":7,"category_id":1}""")
        assertEquals(9L to 7L, transfer.cashAccountId to transfer.toCashAccountId)
        assertEquals("JPY", transfer.currencyCode)
        assertEquals(100L, transfer.amountMinor)
        assertNull(transfer.categoryId)
        val outside = one("""{"type":"TRANSFER","amount":"5","to_account_id":7}""")
        assertNull(outside.cashAccountId)
        assertTrue(AiDraftFlag.ACCOUNT !in outside.flags)
        val nowhere = one("""{"type":"TRANSFER","amount":"5","account_id":7,"to_account_id":7}""")
        assertEquals(7L, nowhere.cashAccountId)
        assertNull(nowhere.toCashAccountId)
        assertTrue(AiDraftFlag.ACCOUNT in one("""{"type":"TRANSFER","amount":"5"}""").flags)
    }

    @Test fun a_transfer_said_by_what_arrived_keeps_that_amount() {
        // 1000 yen arrive in the yen account from the CNY card: what left is worked out at the app's rate.
        val received = one("""{"type":"TRANSFER","amount":"1000","currency":"JPY","account_id":7,"to_account_id":9}""")
        assertNull(received.amountMinor)
        assertEquals(1000L, received.toAmountMinor)
        assertEquals("CNY", received.currencyCode)
        assertTrue(received.hasAmount)
        assertTrue(AiDraftFlag.AMOUNT !in received.flags)
        // Said in the leaving account's currency, or in neither, it is what left.
        assertEquals(1000L, one("""{"type":"TRANSFER","amount":"10","currency":"CNY","account_id":7,"to_account_id":9}""").amountMinor)
        assertEquals(1000L, one("""{"type":"TRANSFER","amount":"10","currency":"USD","account_id":7,"to_account_id":9}""").amountMinor)
    }

    @Test fun a_share_without_a_readable_currency_is_flagged() {
        val share = AiDraftReader(AiSettings(), categories, sent, all, now, "CNY", currencyExpected = true)
        val read = share.read("""{"bills":[{"type":"EXPENSE","amount":"9.9","currency":"usd"},{"type":"EXPENSE","amount":"5","account_id":9}]}""").drafts
        assertEquals("USD", read[0].second.currencyCode)
        assertTrue(AiDraftFlag.CURRENCY !in read[0].second.flags)
        // Not read off the share: the account's currency, flagged to check.
        assertEquals("JPY", read[1].second.currencyCode)
        assertTrue(AiDraftFlag.CURRENCY in read[1].second.flags)
        // In a conversation no currency said is the usual case and not flagged.
        assertTrue(AiDraftFlag.CURRENCY !in one("""{"type":"EXPENSE","amount":"5"}""").flags)
    }

    @Test fun an_expense_keeps_the_currency_paid_on_an_account_in_another() {
        val abroad = one("""{"type":"EXPENSE","amount":"1200","currency":"JPY","category_id":1,"account_id":7}""")
        assertEquals("JPY" to 1200L, abroad.currencyCode to abroad.amountMinor)
        assertEquals(7L, abroad.cashAccountId)
        // Not said: the account's currency.
        assertEquals("JPY", one("""{"type":"EXPENSE","amount":"5","category_id":1,"account_id":9}""").currencyCode)
    }

    @Test fun a_rate_or_charged_amount_the_user_gave_is_kept_across_currencies() {
        // 3 USD on the CNY card at the rate the user gave.
        val rated = one("""{"type":"EXPENSE","amount":"3","currency":"USD","category_id":1,"account_id":7,"rate":"7.10"}""")
        assertEquals(java.math.BigDecimal("7.1"), rated.rate)
        assertNull(rated.accountAmountMinor)
        // What the card was charged wins over a rate, as on the bill page.
        val charged = one("""{"type":"EXPENSE","amount":"3","currency":"USD","category_id":1,"account_id":7,"rate":"7.1","account_amount":"21.35"}""")
        assertEquals(2135L, charged.accountAmountMinor)
        assertNull(charged.rate)
        // One currency, no account, or a rate that is not one: nothing is kept.
        val same = one("""{"type":"EXPENSE","amount":"3","category_id":1,"account_id":7,"rate":"7.1","account_amount":"5"}""")
        assertNull(same.rate); assertNull(same.accountAmountMinor)
        assertNull(one("""{"type":"EXPENSE","amount":"3","currency":"USD","category_id":1,"sub_category_id":12,"rate":"7.1"}""").rate)
        for (bad in listOf("\"-1\"", "\"0\"", "\"abc\"", "\"1e12\"")) assertNull(bad,
            one("""{"type":"EXPENSE","amount":"3","currency":"USD","category_id":1,"account_id":7,"rate":$bad}""").rate)
        // A transfer between currencies: what arrived wins, else the rate.
        val arrived = one("""{"type":"TRANSFER","amount":"100","account_id":7,"to_account_id":9,"account_amount":"2050","rate":"20"}""")
        assertEquals(10000L to 2050L, arrived.amountMinor to arrived.toAmountMinor)
        assertNull(arrived.rate)
        assertEquals(java.math.BigDecimal("20.5"),
            one("""{"type":"TRANSFER","amount":"100","account_id":7,"to_account_id":9,"rate":"20.5"}""").rate)
        // Said by what arrived, with the rate too.
        assertEquals(java.math.BigDecimal("20.5"),
            one("""{"type":"TRANSFER","amount":"2050","currency":"JPY","account_id":7,"to_account_id":9,"rate":"20.5"}""").rate)
    }

    @Test fun only_the_shape_of_the_call_can_fail_it() {
        assertEquals(1, reader().read("""{"bills":"[{\"type\":\"EXPENSE\",\"amount\":\"1\"}]"}""").drafts.size)
        assertTrue(reader().read("""{"message":"Nothing here"}""").drafts.isEmpty())
        assertTrue(reader().read("").drafts.isEmpty())
        // Items that are not objects are skipped; there is a limit to how many are read.
        assertEquals(1, reader().read("""{"bills":[1,{"type":"EXPENSE"}]}""").drafts.size)
        assertEquals(20, reader().read("""{"bills":[${List(30) { "{}" }.joinToString(",")}]}""").drafts.size)
        assertThrows(IllegalArgumentException::class.java) { reader().read("""{"bills":[{"type":""") }
        assertThrows(IllegalArgumentException::class.java) { reader().read("""[1]""") }
        assertThrows(IllegalArgumentException::class.java) { reader().read("""{"bills":{"type":"EXPENSE"}}""") }
    }
}
