package dev.valnook.feature.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class BillImageSizeTextTest {
    @Test fun small_amounts_read_in_kilobytes_and_large_ones_in_megabytes() {
        assertEquals("0 KB", sizeText(0))
        assertEquals("1 KB", sizeText(1))
        assertEquals("48 KB", sizeText(48 * 1024))
        assertEquals("999 KB", sizeText(999 * 1024))
        assertEquals("1.0 MB", sizeText(1024 * 1024 - 1))
        assertEquals("1.0 MB", sizeText(1024 * 1024))
        assertEquals("3.3 MB", sizeText(3 * 1024 * 1024 + 300_000))
    }
}
