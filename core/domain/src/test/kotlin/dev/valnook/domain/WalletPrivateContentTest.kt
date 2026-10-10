package dev.valnook.domain

import dev.valnook.domain.repository.*
import org.junit.Assert.*
import org.junit.Test

class WalletPrivateContentTest {
    @Test fun spacing_can_be_disabled_without_changing_wraps_or_original_symbols() {
        assertEquals(listOf("0000****----1234"),walletNumberLines("0000****----1234",false))
        assertEquals(listOf("1234567890123456789","0123456789012345678"),walletNumberLines("12345678901234567890123456789012345678",false))
        assertEquals(listOf("00 **--"),walletNumberLines("00 **--",false))
    }
    @Test fun cvv_accepts_zero_to_four_ascii_digits_and_preserves_leading_zeroes() {
        listOf("","0","001","0004").forEach { assertTrue(walletCvvValid(it));WalletPrivateContent(cvv1=it,cvv2=it).validated() }
        listOf("12345","12a","-123","１２３","12 3").forEach {
            assertFalse(walletCvvValid(it));assertTrue(runCatching{WalletPrivateContent(cvv1=it).validated()}.isFailure)
        }
        assertEquals("OLD-CVV",WalletPrivateContent(cvv1="OLD-CVV").validated(legacyCvv=true).cvv1)
    }
    @Test fun displayGroupingNeverMutatesSymbolsOrLeadingZeros() {
        assertEquals(emptyList<String>(),walletNumberLines(""))
        assertEquals(listOf("0000 **** ---- 1234"),walletNumberLines("0000****----1234"))
        assertEquals(listOf("123456 7890123456789","0123456789012345678"),walletNumberLines("12345678901234567890123456789012345678"))
        assertEquals(listOf("*--"),walletNumberLines("*--"))
        assertEquals(listOf("123456 7"),walletNumberLines("1234567"))
    }
    @Test fun limitsCountCodepointsAndSensitiveToStringIsRedacted() {
        val value=WalletPrivateContent(number="*".repeat(38),expiry="01/29",cvv1="001",cvv2="002")
        assertEquals(value,value.validated())
        assertTrue(runCatching{value.copy(number="*".repeat(39)).validated()}.isFailure)
        assertTrue(runCatching{value.copy(number="a\nb").validated()}.isFailure)
        assertEquals(value.copy(number="😀".repeat(38)),value.copy(number="😀".repeat(38)).validated())
        assertFalse(value.toString().contains("001"))
        assertFalse(value.toString().contains(value.number))
    }
}
