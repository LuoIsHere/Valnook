package dev.valnook.app.appearance

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.hypot

internal fun readAccountPhoto(context: Context, uri: Uri): Bitmap {
    val input = requireNotNull(context.contentResolver.openInputStream(uri))
    val bytes = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count = stream.read(buffer)
        while (count >= 0) {
            require(output.size() + count <= 32 * 1024 * 1024)
            output.write(buffer, 0, count)
            count = stream.read(buffer)
        }
        output.toByteArray()
    }
    require(dev.valnook.data.image.WalletImages.staticPhoto(bytes))
    return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        require(info.size.width.toLong() * info.size.height <= 64_000_000)
        val factor = min(1f, 1024f / max(info.size.width, info.size.height))
        decoder.setTargetSize(max(1, (info.size.width * factor).toInt()), max(1, (info.size.height * factor).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
}

/** Coordinates are fractions of the square viewport, shared by preview and exported pixels. */
internal fun accountPhotoRect(width: Int, height: Int, side: Float, fit: Boolean,
    zoom: Float, panX: Float, panY: Float): RectF {
    // The full rectangle must fit inside the circle, including the corners of square logos.
    val scale = (if (fit) side / hypot(width.toFloat(), height.toFloat()) * .94f
        else max(side / width, side / height)) * zoom
    val w = width * scale
    val h = height * scale
    val x = (panX * side).coerceIn(-max(0f, (w - side) / 2f), max(0f, (w - side) / 2f))
    val y = (panY * side).coerceIn(-max(0f, (h - side) / 2f), max(0f, (h - side) / 2f))
    return RectF((side - w) / 2f + x, (side - h) / 2f + y, (side + w) / 2f + x, (side + h) / 2f + y)
}

internal fun encodeAccountPhoto(source: Bitmap, fit: Boolean, zoom: Float, panX: Float, panY: Float): ByteArray {
    val result = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    try {
        Canvas(result).drawBitmap(source, null, accountPhotoRect(source.width, source.height, 256f, fit, zoom, panX, panY),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        val output = ByteArrayOutputStream()
        check(result.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, output))
        return output.toByteArray().also { require(it.size <= dev.valnook.domain.model.AccountSymbols.MAX_IMAGE_BYTES) }
    } finally { result.recycle() }
}
