package dev.valnook.domain

import dev.valnook.domain.model.Currency
import org.junit.Assert.*
import org.junit.Test

class CurrencyNamesTest {
    @Test fun every_currency_has_its_own_english_name() {
        Currency.supported.forEach {
            assertNotEquals(it.code, it.english_name)
            assertTrue(it.code, it.english_name.all { char -> char.code < 128 })
        }
        assertEquals(Currency.supported.size, Currency.supported.map { it.english_name }.toSet().size)
    }

    @Test fun options_follow_the_interface_language() {
        assertEquals("EUR" to "欧元", Currency.options("zh").first { it.first == "EUR" })
        assertEquals("EUR" to "Euro", Currency.options("en").first { it.first == "EUR" })
        // Any language without Chinese names falls back to English.
        assertEquals("USD" to "US Dollar", Currency.options("fr").first { it.first == "USD" })
        assertEquals(Currency.supported.map { it.code }, Currency.options("en").map { it.first })
    }
}
