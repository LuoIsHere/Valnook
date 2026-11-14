package dev.valnook.feature.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

enum class AiAvatar { USER, AI }

/**
 * The chat pictures of the user and the AI: a small square WebP each in the app's private files. They are only
 * shown on this device; never sent to the model, never backed up.
 */
class AiAvatars(private val directory: File) {
    private val changes = MutableStateFlow(0)
    /** Bumped on every change, so pictures on screen reload. */
    val version: StateFlow<Int> = changes

    fun file(who: AiAvatar): File? = File(directory, "${who.name.lowercase()}.webp").takeIf { it.isFile }

    /** Crops [bytes] to the middle square and stores it at [SIZE] pixels; false when they are not a picture. */
    suspend fun set(who: AiAvatar, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext false
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext false
        val side = minOf(decoded.width, decoded.height)
        val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return@withContext false
            val target = File(directory, "${who.name.lowercase()}.webp")
            val temporary = File(directory, "${who.name.lowercase()}.tmp")
            temporary.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
            if (!temporary.renameTo(target)) { temporary.delete(); return@withContext false }
        } finally {
            listOf(decoded, square, scaled).distinct().forEach(Bitmap::recycle)
        }
        changes.value++
        true
    }

    suspend fun clear(who: AiAvatar) = withContext(Dispatchers.IO) {
        File(directory, "${who.name.lowercase()}.webp").delete()
        changes.value++
    }

    companion object {
        const val SIZE = 256
        fun directory(context: Context) = File(context.filesDir, "ai_avatars")
    }
}
