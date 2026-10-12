package dev.valnook.domain.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Byte-level card image rules shared by the editor, storage validation and account photos. */
object WalletImageFormat {
    fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun staticPhoto(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte()) return true
        if (bytes.copyOfRange(0,8).contentEquals(byteArrayOf(0x89.toByte(),80,78,71,13,10,26,10))) {
            var offset = 8
            while (offset + 12 <= bytes.size) {
                val length = ByteBuffer.wrap(bytes,offset,4).int
                if (length < 0 || length.toLong() + offset + 12 > bytes.size) return false
                val type = String(bytes,offset+4,4,Charsets.US_ASCII)
                if (type == "acTL") return false
                if (type == "IEND") return true
                offset += length + 12
            }
            return false
        }
        if (String(bytes,0,4,Charsets.US_ASCII) != "RIFF" || String(bytes,8,4,Charsets.US_ASCII) != "WEBP") return false
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val type = String(bytes,offset,4,Charsets.US_ASCII)
            val length = ByteBuffer.wrap(bytes,offset+4,4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || offset.toLong() + 8 + length > bytes.size) return false
            if (type == "ANIM" || type == "ANMF" || (type == "VP8X" && length > 0 && bytes[offset+8].toInt() and 2 != 0)) return false
            offset += 8 + length + (length and 1)
        }
        return offset == bytes.size
    }
}
