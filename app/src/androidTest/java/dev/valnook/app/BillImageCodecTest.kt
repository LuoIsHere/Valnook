package dev.valnook.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.domain.model.BillImageRules
import dev.valnook.feature.bills.BillImageCodec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.random.Random

/** A picked picture comes out upright, within the limits, as a WebP with no metadata; synthetic pictures only. */
@RunWith(AndroidJUnit4::class)
class BillImageCodecTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val files = mutableListOf<File>()
    @After fun clean() { files.forEach { it.delete() } }

    private fun file(bytes: ByteArray, suffix: String) =
        File(context.cacheDir, "codec-${UUID.randomUUID()}.$suffix").apply { writeBytes(bytes); files += this }
    private fun prepare(file: File) = runBlocking { BillImageCodec.prepare(context.contentResolver, Uri.fromFile(file)) }
    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 95) =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it); bitmap.recycle() }.toByteArray()

    /** The chunk names of a RIFF WebP, so a test can see nothing but picture data is in it. */
    private fun chunks(webp: ByteArray): List<String> {
        assertEquals("RIFF", String(webp, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(webp, 8, 4, Charsets.US_ASCII))
        val names = mutableListOf<String>()
        var offset = 12
        while (offset + 8 <= webp.size) {
            names += String(webp, offset, 4, Charsets.US_ASCII)
            val length = ByteBuffer.wrap(webp, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            offset += 8 + length + (length and 1)
        }
        return names
    }

    /** [jpeg] with an EXIF block saying "rotate 90° clockwise" and a made-up camera text, as a phone writes it. */
    private fun withOrientation(jpeg: ByteArray): ByteArray {
        val tiff = ByteBuffer.allocate(8 + 2 + 12 * 2 + 4 + 16).order(ByteOrder.BIG_ENDIAN).apply {
            put("MM".toByteArray()); putShort(42); putInt(8)
            putShort(2)
            putShort(0x010f); putShort(2); putInt(16); putInt(8 + 2 + 24 + 4)         // Make: text below
            putShort(0x0112); putShort(3); putInt(1); putShort(6); putShort(0)        // Orientation: 6
            putInt(0)
            put("SyntheticCamera\u0000".toByteArray(Charsets.US_ASCII))
        }.array()
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + tiff
        val segment = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN)
            .put(0xff.toByte()).put(0xe1.toByte()).putShort((payload.size + 2).toShort()).put(payload).array()
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    @Test fun a_turned_camera_photo_comes_out_upright_without_its_exif() {
        // Wider than tall, red on the left: turned upright it is taller than wide with red on top.
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.BLUE); drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.RED }) }
        val out = prepare(file(withOrientation(compress(bitmap, Bitmap.CompressFormat.JPEG)), "jpg"))
        assertTrue(chunks(out).none { it == "EXIF" || it == "XMP " })
        assertTrue(String(out, Charsets.ISO_8859_1).indexOf("SyntheticCamera") < 0)
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(200 to 400, decoded.width to decoded.height)
        val top = decoded.getPixel(100, 50)
        assertTrue("top should be red", Color.red(top) > 200 && Color.blue(top) < 80)
    }

    @Test fun a_large_transparent_screenshot_shrinks_to_two_million_pixels_on_white() {
        val bitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawRect(1000f, 1000f, 3000f, 2000f, Paint().apply { color = Color.BLACK })
        val out = prepare(file(compress(bitmap, Bitmap.CompressFormat.PNG), "png"))
        assertTrue(chunks(out).none { it == "ALPH" || it == "EXIF" || it == "XMP " })
        val decoded = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertEquals(1632 to 1224, decoded.width to decoded.height)
        val corner = decoded.getPixel(5, 5)
        assertTrue("transparent parts turn white", Color.red(corner) > 240 && Color.green(corner) > 240 && Color.blue(corner) > 240)
    }

    @Test fun a_picture_that_compresses_badly_still_fits_the_size_limit() {
        // Noise is the worst case for compression: lower quality, then a smaller size, bring it within the limit.
        val random = Random(42)
        val bitmap = Bitmap.createBitmap(3000, 3000, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(3000 * 3000) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        bitmap.setPixels(pixels, 0, 3000, 0, 0, 3000, 3000)
        val out = prepare(file(compress(bitmap, Bitmap.CompressFormat.PNG, 100), "png"))
        assertTrue("${out.size} bytes", out.size <= BillImageRules.MAX_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(out, 0, out.size, bounds)
        assertTrue(bounds.outWidth.toLong() * bounds.outHeight in 800_000L..BillImageRules.MAX_PIXELS.toLong())
    }

    @Test fun moving_pictures_are_refused() {
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(64)
        try { prepare(file(gif, "gif")); fail("A GIF should be refused") } catch (_: IllegalArgumentException) { }
    }
}
