package dev.valnook.data.cloud

import java.util.Base64

/**
 * OneDrive's QuickXorHash, as Microsoft publishes it: bytes are XORed into a 160-bit circle, each one 11 bits further
 * round, and the length is XORed into the last 8 bytes. OneDrive reports it for every file, so a picture's upload is
 * checked against it without downloading the file back.
 */
internal class QuickXorHash {
    private val data = LongArray(3)
    private var length = 0L
    private var shift = 0

    fun update(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size): QuickXorHash {
        var cell = shift / 64
        var bit = shift % 64
        val iterations = minOf(count, WIDTH)
        for (i in 0 until iterations) {
            val last = cell == data.size - 1
            val bitsInCell = if (last) LAST_CELL_BITS else 64
            if (bit <= bitsInCell - 8) {
                var j = offset + i
                while (j < offset + count) {
                    data[cell] = data[cell] xor ((bytes[j].toLong() and 0xff) shl bit)
                    j += WIDTH
                }
            } else {
                val next = if (last) 0 else cell + 1
                var folded = 0
                var j = offset + i
                while (j < offset + count) {
                    folded = folded xor (bytes[j].toInt() and 0xff)
                    j += WIDTH
                }
                data[cell] = data[cell] xor (folded.toLong() shl bit)
                data[next] = data[next] xor (folded.toLong() ushr (bitsInCell - bit))
            }
            bit += SHIFT
            while (bit >= bitsInCell) {
                cell = if (last) 0 else cell + 1
                bit -= bitsInCell
            }
        }
        shift = ((shift + SHIFT.toLong() * (count % WIDTH)) % WIDTH).toInt()
        length += count
        return this
    }

    /** The hash as OneDrive shows it: 20 bytes, Base64. */
    fun digest(): String {
        val out = ByteArray(WIDTH / 8)
        for (i in 0 until data.size - 1) for (b in 0 until 8) out[i * 8 + b] = (data[i] ushr (8 * b)).toByte()
        for (b in 0 until out.size - (data.size - 1) * 8) out[(data.size - 1) * 8 + b] = (data[data.size - 1] ushr (8 * b)).toByte()
        for (b in 0 until 8) out[out.size - 8 + b] = (out[out.size - 8 + b].toInt() xor (length ushr (8 * b)).toInt()).toByte()
        return Base64.getEncoder().encodeToString(out)
    }

    companion object {
        private const val WIDTH = 160
        private const val SHIFT = 11
        private const val LAST_CELL_BITS = 32

        fun of(bytes: ByteArray): String = QuickXorHash().update(bytes).digest()
    }
}
