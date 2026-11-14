package dev.valnook.data.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.valnook.data.image.BillImageStore
import dev.valnook.domain.ai.AiRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Pictures for the model and the conversation, read from the bill picture library. Encoding a new JPEG from the
 * decoded pixels carries no metadata over.
 */
class StoreAiPictures(private val store: BillImageStore) : AiPictureSource, AiPicturePins {
    override suspend fun forModel(key: String): ByteArray? = encode(key, AiRules.IMAGE_EDGE, AiRules.IMAGE_QUALITY)

    override suspend fun thumbnail(key: String): ByteArray? = encode(key, AiRules.THUMB_EDGE, THUMB_QUALITY)

    override fun pin(keys: Collection<String>) = store.pin(keys)

    override suspend fun release(keys: Collection<String>) = store.discard(keys)

    private suspend fun encode(key: String, edge: Int, quality: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!store.has(key)) return@withContext null
        val bytes = runCatching { store.file(key).readBytes() }.getOrNull() ?: return@withContext null
        scaledJpeg(bytes, edge, quality)
    }

    companion object {
        private const val THUMB_QUALITY = 75

        /** [bytes] decoded and drawn again as a JPEG whose longest edge is at most [edge]; null when unreadable. */
        fun scaledJpeg(bytes: ByteArray, edge: Int, quality: Int): ByteArray? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longest = maxOf(decoded.width, decoded.height)
            val scaled = if (longest <= edge) decoded else Bitmap.createScaledBitmap(decoded,
                (decoded.width.toLong() * edge / longest).toInt().coerceAtLeast(1),
                (decoded.height.toLong() * edge / longest).toInt().coerceAtLeast(1), true)
            // A picture with transparency gets a white background rather than black.
            val opaque = if (scaled.hasAlpha()) Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                android.graphics.Canvas(it).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(scaled, 0f, 0f, null) }
            } else scaled
            return ByteArrayOutputStream().use { out ->
                opaque.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }.also {
                if (opaque !== scaled) opaque.recycle()
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }
    }
}
