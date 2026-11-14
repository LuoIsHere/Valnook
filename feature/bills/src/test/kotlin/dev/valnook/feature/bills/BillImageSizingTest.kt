package dev.valnook.feature.bills

import dev.valnook.domain.model.BillImageRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BillImageSizingTest {
    @Test fun photos_shrink_to_two_million_pixels_and_long_shots_to_three_thousand_on_the_long_edge() {
        assertEquals(1632 to 1224, BillImageSizing.target(4000, 3000))
        assertEquals(1224 to 1632, BillImageSizing.target(3000, 4000))
        // A long screenshot keeps its width as far as the long edge allows, so its text stays readable.
        assertEquals(540 to 3000, BillImageSizing.target(1080, 6000))
        assertEquals(3000 to 85, BillImageSizing.target(3500, 100))
        // Small pictures are never enlarged.
        assertEquals(800 to 600, BillImageSizing.target(800, 600))
        assertEquals(1 to 1, BillImageSizing.target(1, 1))
    }

    @Test fun every_target_is_a_size_a_bill_can_hold() {
        listOf(4000 to 3000, 4032 to 3024, 8000 to 6000, 1080 to 20000, 12000 to 9000, 5000 to 5000, 2001 to 1000).forEach { (w, h) ->
            val (tw, th) = BillImageSizing.target(w, h)
            assertTrue("$w x $h", tw.toLong() * th <= BillImageRules.MAX_PIXELS)
            assertTrue("$w x $h", tw <= BillImageRules.MAX_EDGE && th <= BillImageRules.MAX_EDGE)
        }
    }

    @Test fun shrinking_steps_by_a_fifth_and_stops_above_eight_hundred_thousand_pixels() {
        assertEquals(1305 to 979, BillImageSizing.smaller(1632, 1224))
        assertEquals(1044 to 783, BillImageSizing.smaller(1305, 979))
        // 835 x 626 would be about 523 thousand pixels: too small to read a receipt.
        assertNull(BillImageSizing.smaller(1044, 783))
    }

    @Test fun only_still_pictures_in_known_formats_are_read() {
        fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray() + ByteArray(32)
        fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
        assertTrue(BillImageSizing.readable(bytes(0xff, 0xd8, 0xff, 0xe0)))
        assertTrue(BillImageSizing.readable(ByteArray(4) + ascii("ftypheic") + ByteArray(20)))
        assertTrue(BillImageSizing.readable(ByteArray(4) + ascii("ftypmif1") + ByteArray(20)))
        // A HEIF image sequence is a moving picture; GIF is not taken at all.
        assertFalse(BillImageSizing.readable(ByteArray(4) + ascii("ftypmsf1") + ByteArray(20)))
        assertFalse(BillImageSizing.readable(ascii("GIF89a") + ByteArray(26)))
        assertFalse(BillImageSizing.readable(ByteArray(4)))
    }
}
