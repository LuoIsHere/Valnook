package dev.valnook.domain

import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.domain.model.BillCategoryPresets
import dev.valnook.domain.model.BillColors
import dev.valnook.domain.model.BillRules
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigDecimal

class BillRulesTest {
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val jpy = Currency.of("JPY")

    @Test fun conversion_rounds_half_up_to_the_target_currency() {
        assertEquals(14_247L, BillRules.convert(2_000, usd, cny, BigDecimal("7.1234")))
        // JPY has no minor unit: CNY 10.05 at 20.5 is 206.025 yen.
        assertEquals(206L, BillRules.convert(1_005, cny, jpy, BigDecimal("20.5")))
        assertEquals(1_000L, BillRules.convert(1_000, cny, cny, BigDecimal.ONE))
        try { BillRules.convert(Long.MAX_VALUE, cny, jpy, BigDecimal("1000")); fail() }
        catch (e: DomainException) { assertEquals(ErrorCode.OVERFLOW, e.code) }
    }

    @Test fun a_rate_worked_out_from_the_amount_moved_gives_that_amount_back() {
        val rate = BillRules.derivedRate(2_000, usd, 14_500, cny)
        assertEquals(0, BigDecimal("7.25").compareTo(rate))
        val odd = BillRules.derivedRate(3_300, usd, 23_456, cny)
        assertTrue(odd.scale() <= BillRules.RATE_SCALE)
        assertEquals(23_456L, BillRules.convert(3_300, usd, cny, odd))
    }

    @Test fun names_and_notes_are_trimmed_and_bounded() {
        assertEquals("Pets", BillRules.categoryName("  Pets "))
        try { BillRules.categoryName(" "); fail() } catch (e: DomainException) { assertEquals(ErrorCode.NAME, e.code) }
        assertEquals(40, BillRules.counterpartyOrTruncated("名".repeat(50)).length)
        assertEquals("Bank · A", BillRules.counterpartyOrTruncated(" Bank · A "))
    }

    @Test fun presets_have_unique_keys_known_parents_icons_and_colours() {
        val all = BillCategoryPresets.all
        assertEquals(all.size, all.map { it.key }.toSet().size)
        all.filter { it.parentKey != null }.forEach { child ->
            val parent = BillCategoryPresets.preset(child.parentKey)
            assertEquals(child.key, child.kind, parent?.kind)
            assertNull(child.key, parent?.parentKey)
            // A parent comes before its children, so it is stored first.
            assertTrue(all.indexOf(parent) < all.indexOf(child))
        }
        assertTrue(all.all { it.color in BillColors.keys && it.chinese.isNotBlank() && it.english.isNotBlank() })
        assertTrue(all.any { it.kind == BillCategoryKind.INCOME })
        assertEquals("手续费", BillCategoryPresets.name("finance.fees", english = false))
    }
}
