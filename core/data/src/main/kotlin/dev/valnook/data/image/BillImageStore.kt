package dev.valnook.data.image

import android.graphics.BitmapFactory
import dev.valnook.data.database.BillImageFileEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillImageRules
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.WalletImageFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The bill pictures on this device: one WebP file per picture in [dir], named by the SHA-256 of its bytes, so bills
 * holding the same picture share it. Files are written once and never changed. Only this app reads them.
 *
 * A picture is deleted as soon as no bill holds it, except while it is pinned (picked in an open bill form) or while
 * something holds the store (a backup, restore or clean-up reading the files); [sweep] then catches it later.
 */
class BillImageStore(private val dir: File, private val db: ValnookDatabase, private val clock: Clock) {
    private val dao = db.bills()
    private val lock = Mutex()
    private val holds = AtomicInteger(0)
    private val pinned = ConcurrentHashMap<String, Int>()

    fun file(key: String): File {
        require(BillImageRules.validKey(key))
        return File(dir, "$key.webp")
    }

    fun has(key: String): Boolean = BillImageRules.validKey(key) && File(dir, "$key.webp").isFile

    /**
     * Checks [bytes] are a picture a bill can hold and stores them, pinned until [unpin] or [discard]. The same picture
     * is stored once; storing it again keeps the time it was first saved here.
     */
    suspend fun put(bytes: ByteArray): BillImage = withContext(Dispatchers.IO) {
        val image = inspect(bytes) ?: throw DomainException(ErrorCode.FORMAT)
        pin(listOf(image.key))
        lock.withLock {
            dir.mkdirs()
            val target = file(image.key)
            val existed = target.isFile && target.length() == bytes.size.toLong()
            if (!existed) write(target, bytes)
            val old = dao.imageFile(image.key)
            val saved = old?.local_saved_at_ms?.takeIf { existed } ?: clock.millis()
            dao.putImageFile((old ?: BillImageFileEntity(image.key, null, null, null, null, null)).copy(local_saved_at_ms = saved))
        }
        image
    }

    /**
     * Stores picture [key] fetched back from OneDrive, when [bytes] really are it; it is not pinned, and its clean-up
     * clock starts again from now. Returns whether it was stored.
     */
    suspend fun putFetched(key: String, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val image = inspect(bytes)?.takeIf { it.key == key } ?: return@withContext false
        lock.withLock {
            dir.mkdirs()
            val target = file(image.key)
            if (!(target.isFile && target.length() == bytes.size.toLong())) write(target, bytes)
            val old = dao.imageFile(key)
            dao.putImageFile((old ?: BillImageFileEntity(key, null, null, null, null, null)).copy(local_saved_at_ms = clock.millis()))
        }
        true
    }

    /** A file whose bytes no longer match its name is no use: it goes, so the picture can come back from OneDrive. */
    suspend fun dropCorrupt(key: String) = withContext(Dispatchers.IO) { lock.withLock { drop(key) } }

    /** The stored picture [key] as a bill holds it; null when it is not on this device or not a valid picture. */
    suspend fun describe(key: String): BillImage? = withContext(Dispatchers.IO) {
        if (!has(key)) return@withContext null
        val target = file(key)
        if (target.length() !in 1..BillImageRules.MAX_BYTES.toLong()) return@withContext null
        inspect(runCatching { target.readBytes() }.getOrNull() ?: return@withContext null)?.takeIf { it.key == key }
    }

    /** Keeps [keys] from being deleted while a form holds them unsaved. */
    fun pin(keys: Collection<String>) = keys.forEach { pinned.merge(it, 1, Int::plus) }

    /** The form saved or no longer holds [keys]. */
    fun unpin(keys: Collection<String>) = keys.forEach { key -> pinned.computeIfPresent(key) { _, n -> (n - 1).takeIf { it > 0 } } }

    /** A form dropped [keys] without saving: unpins them and deletes those no bill holds. */
    suspend fun discard(keys: Collection<String>) { unpin(keys); release(keys) }

    /** Keeps every file while the returned handle is open, for a backup, restore or clean-up reading them. */
    fun hold(): AutoCloseable {
        holds.incrementAndGet()
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        return AutoCloseable { if (open.getAndSet(false)) holds.decrementAndGet() }
    }

    /** Deletes the files of [keys] that no bill holds and no form has pinned, unless the store is held. */
    suspend fun release(keys: Collection<String>) {
        if (keys.isEmpty() || holds.get() > 0) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                keys.filter { BillImageRules.validKey(it) && !pinned.containsKey(it) && !dao.imageInUse(it) }.forEach { drop(it) }
            }
        }
    }

    /**
     * Startup clean-up: files no bill holds that were saved more than a day ago (a form left by a crash), stray
     * temporary files, and records of files that are gone. Skipped while the store is held.
     */
    suspend fun sweep() {
        if (holds.get() > 0) return
        withContext(Dispatchers.IO) {
            lock.withLock {
                val now = clock.millis()
                val used = dao.imageKeysInUse().toHashSet()
                val records = dao.imageFiles().associateBy { it.image_key }
                dir.listFiles()?.forEach { file ->
                    val key = file.name.removeSuffix(".webp")
                    val picture = file.name.endsWith(".webp") && BillImageRules.validKey(key)
                    if (!picture) {
                        if (now - file.lastModified() > GRACE_MS) file.delete()
                        return@forEach
                    }
                    if (key in used || pinned.containsKey(key)) return@forEach
                    val saved = records[key]?.local_saved_at_ms ?: file.lastModified()
                    if (now - saved > GRACE_MS) drop(key)
                }
                records.values.filter { it.local_saved_at_ms != null && !File(dir, "${it.image_key}.webp").isFile }
                    .forEach { forgetLocal(it) }
            }
        }
    }

    /** Deletes every picture file, once the data that held them is cleared. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        lock.withLock { dir.listFiles()?.forEach { it.delete() } }
    }

    private suspend fun drop(key: String) {
        File(dir, "$key.webp").delete()
        dao.imageFile(key)?.let { forgetLocal(it) }
    }

    /** The file is no longer here; the record stays only while it still says something about the cloud copy. */
    private suspend fun forgetLocal(record: BillImageFileEntity) {
        if (record.cloud_size == null && record.cloud_delete_requested_at_ms == null) dao.deleteImageFile(record.image_key)
        else dao.putImageFile(record.copy(local_saved_at_ms = null))
    }

    private fun write(target: File, bytes: ByteArray) {
        val temp = File(dir, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temp).use { out -> out.write(bytes); out.fd.sync() }
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw IOException("Cannot store picture")
            }
        } finally {
            temp.delete()
        }
    }

    companion object {
        /** How long a picture no bill holds stays before the startup clean-up deletes it. */
        const val GRACE_MS = 24L * 60 * 60 * 1000

        /** [bytes] as a picture a bill can hold: a static WebP within the limits and with no EXIF or XMP; else null. */
        fun inspect(bytes: ByteArray): BillImage? {
            if (bytes.isEmpty() || bytes.size > BillImageRules.MAX_BYTES || !webpWithoutMetadata(bytes) ||
                !WalletImageFormat.staticPhoto(bytes)) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outMimeType != "image/webp") return null
            val image = BillImage(WalletImageFormat.digest(bytes), bytes.size.toLong(), bounds.outWidth, bounds.outHeight)
            return image.takeIf(BillImageRules::valid)
        }

        /** A RIFF WebP whose chunks are well formed and include no EXIF or XMP metadata. */
        fun webpWithoutMetadata(bytes: ByteArray): Boolean {
            if (bytes.size < 20 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(bytes, 8, 4, Charsets.US_ASCII) != "WEBP") return false
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val type = String(bytes, offset, 4, Charsets.US_ASCII)
                val length = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (length < 0 || offset.toLong() + 8 + length > bytes.size) return false
                if (type == "EXIF" || type == "XMP ") return false
                offset += 8 + length + (length and 1)
            }
            return offset == bytes.size
        }
    }
}
