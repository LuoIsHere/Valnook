package dev.valnook.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64
import kotlin.random.Random

class QuickXorHashTest {
    private fun hash(base64: String) = QuickXorHash.of(Base64.getDecoder().decode(base64))

    @Test fun short_inputs_match_the_published_values() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAA=", hash(""))
        assertEquals("SgAAAAAAAAAAAAAAAQAAAAAAAAA=", hash("Sg=="))
        assertEquals("taAFAAAAAAAAAAAAAgAAAAAAAAA=", hash("tbQ="))
        assertEquals("0rDEEwAAAAAAAAAAAwAAAAAAAAA=", hash("0pZP"))
    }

    @Test fun hashing_in_pieces_gives_the_same_value_as_at_once() {
        val bytes = Random(7).nextBytes(10_000)
        val whole = QuickXorHash.of(bytes)
        listOf(1, 7, 159, 160, 161, 4096).forEach { size ->
            val pieces = QuickXorHash()
            var offset = 0
            while (offset < bytes.size) {
                val count = minOf(size, bytes.size - offset)
                pieces.update(bytes, offset, count)
                offset += count
            }
            assertEquals("pieces of $size", whole, pieces.digest())
        }
    }
}
