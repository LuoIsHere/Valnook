package dev.valnook.feature.bills

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import dev.valnook.domain.model.BillImageRules
import dev.valnook.domain.model.WalletImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** The sizes and formats a picked picture goes through; plain arithmetic, so it is tested off the device. */
internal object BillImageSizing {
    /** A picture larger than this (in bytes) is not read at all. */
    const val MAX_INPUT_BYTES = 40 * 1024 * 1024
    /** Pictures with more pixels than this are refused rather than decoded. */
    const val MAX_INPUT_PIXELS = 100_000_000L
    /** Lower quality, then a smaller size, are tried while a picture is over this. */
    const val SOFT_BYTES = 450 * 1024
    /** Shrinking stops before a picture has fewer pixels than this. */
    const val MIN_PIXELS = 800_000L
    val QUALITIES = listOf(80, 70, 60)

    /** The size a [width] by [height] picture is stored at: at most 2 million pixels and 3000 on its long edge, never enlarged. */
    fun target(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val pixels = width.toLong() * height
        var scale = 1.0
        if (pixels > BillImageRules.MAX_PIXELS) scale = sqrt(BillImageRules.MAX_PIXELS.toDouble() / pixels)
        val edge = max(width, height)
        if (edge * scale > BillImageRules.MAX_EDGE) scale = BillImageRules.MAX_EDGE.toDouble() / edge
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }

    /** The next, smaller size to try, or null once it would drop below [MIN_PIXELS]. */
    fun smaller(width: Int, height: Int): Pair<Int, Int>? {
        val next = max(1, floor(width * 0.8).toInt()) to max(1, floor(height * 0.8).toInt())
        return next.takeIf { it.first.toLong() * it.second >= MIN_PIXELS }
    }

    /** Whether [header] starts a picture that can be read: JPEG, PNG, a still WebP or a single HEIF/HEIC image. */
    fun readable(header: ByteArray): Boolean = WalletImageFormat.staticPhoto(header) || heif(header)

    private fun heif(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || String(bytes, 4, 4, Charsets.US_ASCII) != "ftyp") return false
        return String(bytes, 8, 4, Charsets.US_ASCII) in setOf("heic", "heix", "mif1", "heim", "heis")
    }
}

/** Turns a picked or photographed picture into what a bill stores: a WebP within the limits, without metadata. */
object BillImageCodec {
    /** The stored form of the picture at [uri]; throws when it cannot be read or brought within the limits. */
    suspend fun prepare(resolver: ContentResolver, uri: Uri): ByteArray = withContext(Dispatchers.Default) {
        val input = withContext(Dispatchers.IO) {
            requireNotNull(resolver.openInputStream(uri)).use { stream ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    require(out.size() + n <= BillImageSizing.MAX_INPUT_BYTES)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
        }
        require(BillImageSizing.readable(input))
        var size: Pair<Int, Int>? = null
        // Decoding straight to the stored size keeps memory low; the decoder also turns the picture upright.
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(input))) { decoder, info, _ ->
            require(info.size.width.toLong() * info.size.height <= BillImageSizing.MAX_INPUT_PIXELS)
            val target = BillImageSizing.target(info.size.width, info.size.height)
            size = target
            decoder.setTargetSize(target.first, target.second)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try { encode(decoded, requireNotNull(size)) } finally { decoded.recycle() }
    }

    /** [source] at [size] and below, at falling quality, until it fits; re-encoding drops every bit of metadata. */
    internal fun encode(source: Bitmap, size: Pair<Int, Int>): ByteArray {
        var current: Pair<Int, Int>? = size
        var last: ByteArray? = null
        while (current != null) {
            val (width, height) = current
            // Transparent parts become white, as on paper; the stored picture has no alpha.
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.setHasAlpha(false)
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(source, null, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                }
                for (quality in BillImageSizing.QUALITIES) {
                    val out = ByteArrayOutputStream()
                    check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out))
                    last = out.toByteArray()
                    if (last.size <= BillImageSizing.SOFT_BYTES) return last
                }
            } finally { bitmap.recycle() }
            current = BillImageSizing.smaller(width, height)
        }
        return requireNotNull(last).also { require(it.size <= BillImageRules.MAX_BYTES) }
    }
}
