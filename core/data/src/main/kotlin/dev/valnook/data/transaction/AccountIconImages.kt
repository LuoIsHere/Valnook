package dev.valnook.data.transaction

import android.graphics.BitmapFactory
import dev.valnook.domain.model.AccountSymbols
import java.security.MessageDigest

internal object AccountIconImages {
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    fun valid(id: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes.size > AccountSymbols.MAX_IMAGE_BYTES || id != digest(bytes)) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth != 256 || options.outHeight != 256 ||
            options.outMimeType !in setOf("image/webp", "image/png")) return false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return false
        bitmap.recycle()
        return true
    }
}
