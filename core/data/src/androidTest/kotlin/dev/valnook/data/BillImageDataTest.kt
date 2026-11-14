package dev.valnook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.image.BillImageStore
import dev.valnook.data.portability.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.*
import java.util.UUID
import java.util.zip.ZipInputStream

/** Bill pictures are stored once by content, follow their bills, go when no bill holds them, and back up as references. */
@RunWith(AndroidJUnit4::class)
class BillImageDataTest {
    private class MutableClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Asia/Shanghai")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = at
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = MutableClock(Instant.parse("2026-11-14T04:00:00Z"))
    private lateinit var db: ValnookDatabase
    private lateinit var dir: File
    private lateinit var store: BillImageStore
    private lateinit var commands: RoomFinancialCommands
    private val names = mutableListOf<String>()
    private val dirs = mutableListOf<File>()
    private fun id() = UUID.randomUUID().toString()
    private fun database(): ValnookDatabase {
        val name = "bill-image-test-${id()}.db"; names += name
        return Room.databaseBuilder(context, ValnookDatabase::class.java, name).addCallback(ValnookDatabase.seed).build()
    }
    private fun folder() = File(context.cacheDir, "bill-image-test-${id()}").also { dirs += it }

    @Before fun prepare() {
        db = database(); dir = folder(); store = BillImageStore(dir, db, clock)
        commands = RoomFinancialCommands(db, clock, store)
    }
    @After fun close() { db.close(); names.forEach(context::deleteDatabase); dirs.forEach { it.deleteRecursively() } }

    /** A synthetic receipt-like picture; [shade] makes each one different. */
    private fun picture(shade: Int, width: Int = 240, height: Int = 320,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSY): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(250, 250, 245))
            drawRect(10f, 10f, width - 10f, 40f + shade, Paint().apply { color = Color.rgb(shade, 80, 160) })
        }
        return ByteArrayOutputStream().also { bitmap.compress(format, 80, it); bitmap.recycle() }.toByteArray()
    }

    /** [webp] with an EXIF chunk appended, as a camera would leave it. */
    private fun withExif(webp: ByteArray): ByteArray {
        val exif = "Exif\u0000\u0000synthetic".toByteArray(Charsets.US_ASCII)
        val chunk = ByteBuffer.allocate(8 + exif.size + (exif.size and 1)).order(ByteOrder.LITTLE_ENDIAN)
            .put("EXIF".toByteArray(Charsets.US_ASCII)).putInt(exif.size).put(exif).array()
        val out = webp + chunk
        ByteBuffer.wrap(out, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(out.size - 8)
        return out
    }

    private suspend fun cash(): Long {
        val bank = commands.execute(SaveAccount(id(), null, null, "Synthetic Bank", "", listOf(
            CashBalanceChange("CNY", 1_000_000, null, name = "A")))).id
        return RoomOverview(db).snapshot().cash.single { it.account_id == bank }.id
    }
    private fun category(key: String) = db.openHelper.readableDatabase
        .query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }
    private fun expense(cash: Long, images: List<BillImage>?, sub: String = "food.lunch") =
        BillDraft(BillType.EXPENSE, clock.millis(), 1_000, "CNY", 0, cash, 1_000, categoryId = category("food"),
            subCategoryId = category(sub), note = "Synthetic lunch", images = images)
    private suspend fun save(draft: BillDraft, billId: Long? = null) =
        commands.execute(SaveBill(id(), billId, billId?.let { db.bills().bill(it)!!.revision }, draft)).id
    private suspend fun delete(billId: Long) = commands.execute(DeleteBill(id(), billId, db.bills().bill(billId)!!.revision))
    private suspend fun keys(billId: Long) = db.bills().attachments(billId).map { it.image_key }
    private fun files() = dir.listFiles().orEmpty().map { it.name }.toSet()
    private suspend fun expect(code: ErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: DomainException) { assertEquals(code, e.code) }
    }

    @Test fun only_plain_webp_pictures_within_limits_are_stored_and_each_once() = runBlocking {
        expect(ErrorCode.FORMAT) { store.put(picture(1, format = Bitmap.CompressFormat.PNG)) }
        expect(ErrorCode.FORMAT) { store.put(picture(1, format = Bitmap.CompressFormat.JPEG)) }
        expect(ErrorCode.FORMAT) { store.put(withExif(picture(1))) }
        expect(ErrorCode.FORMAT) { store.put(picture(1, width = BillImageRules.MAX_EDGE + 1, height = 8)) }
        val bytes = picture(2)
        val first = store.put(bytes)
        assertEquals(WalletImageFormat.digest(bytes), first.key)
        assertEquals(240 to 320, first.width to first.height)
        val saved = clock.millis()
        clock.at = clock.at.plusSeconds(60)
        assertEquals(first, store.put(bytes))
        assertEquals(setOf("${first.key}.webp"), files())
        assertEquals(saved, db.bills().imageFile(first.key)!!.local_saved_at_ms)
        assertEquals(first, store.describe(first.key))
    }

    @Test fun a_bill_keeps_its_pictures_in_order_and_lets_go_of_those_it_drops() = runBlocking {
        val cash = cash()
        val (p1, p2, p3) = listOf(1, 2, 3).map { store.put(picture(it * 40)) }
        val bill = save(expense(cash, listOf(p1, p2)))
        store.unpin(listOf(p1, p2, p3).map { it.key })
        assertEquals(listOf(p1.key, p2.key), keys(bill))
        assertEquals(2, RoomBills(db).observeBill(bill).first()!!.imageCount)
        assertEquals(listOf(p1, p2), RoomBills(db).observeImages(bill).first())
        // A save that does not deal with pictures leaves them alone.
        save(expense(cash, null).copy(note = "Synthetic dinner"), bill)
        assertEquals(listOf(p1.key, p2.key), keys(bill))
        save(expense(cash, listOf(p2, p1, p3)), bill)
        assertEquals(listOf(p2.key, p1.key, p3.key), keys(bill))
        assertEquals(3, files().size)
        save(expense(cash, listOf(p3)), bill)
        assertEquals(listOf(p3.key), keys(bill))
        assertEquals(setOf("${p3.key}.webp"), files())
    }

    @Test fun a_picture_two_bills_share_goes_only_with_the_last() = runBlocking {
        val cash = cash()
        val p = store.put(picture(90))
        val one = save(expense(cash, listOf(p)))
        val two = save(expense(cash, listOf(p)))
        store.unpin(listOf(p.key, p.key))
        delete(one)
        assertTrue(store.has(p.key))
        assertTrue(keys(one).isEmpty())
        delete(two)
        assertFalse(store.has(p.key))
        assertNull(db.bills().imageFile(p.key))
    }

    @Test fun pinned_or_held_pictures_wait_and_the_sweep_takes_them_a_day_later() = runBlocking {
        val cash = cash()
        // Picked in a form and dropped with the bill: kept while the form holds it, gone when the form lets go.
        val pinned = store.put(picture(10))
        delete(save(expense(cash, listOf(pinned))))
        assertTrue(store.has(pinned.key))
        store.discard(listOf(pinned.key))
        assertFalse(store.has(pinned.key))
        // Dropped while a backup reads the files: left for the sweep, which waits a day from when it was saved.
        val held = store.put(picture(20))
        val bill = save(expense(cash, listOf(held)))
        store.unpin(listOf(held.key))
        store.hold().use { delete(bill) }
        assertTrue(store.has(held.key))
        store.sweep()
        assertTrue(store.has(held.key))
        clock.at = clock.at.plusMillis(BillImageStore.GRACE_MS + 1)
        store.sweep()
        assertFalse(store.has(held.key))
        assertNull(db.bills().imageFile(held.key))
    }

    @Test fun the_sweep_clears_stray_files_and_forgets_files_that_are_gone() = runBlocking {
        val cash = cash()
        val kept = store.put(picture(30))
        val bill = save(expense(cash, listOf(kept)))
        store.unpin(listOf(kept.key))
        val stray = File(dir, "${kept.key}.webp.${id()}.tmp").apply { writeText("partial"); setLastModified(clock.millis() - BillImageStore.GRACE_MS - 1) }
        // A picture that went missing on disk but is known in the cloud keeps its cloud record.
        db.bills().putImageFile(db.bills().imageFile(kept.key)!!.copy(cloud_size = 10, cloud_quick_xor = "x", cloud_seen_at_ms = 1))
        store.file(kept.key).delete()
        clock.at = clock.at.plusMillis(BillImageStore.GRACE_MS + 1)
        store.sweep()
        assertFalse(stray.exists())
        val record = db.bills().imageFile(kept.key)!!
        assertNull(record.local_saved_at_ms)
        assertEquals(10L, record.cloud_size)
        assertEquals(listOf(kept.key), keys(bill))
    }

    @Test fun a_save_only_takes_pictures_stored_here_at_most_four_and_each_once() = runBlocking {
        val cash = cash()
        val stored = (1..5).map { store.put(picture(it * 30)) }
        val elsewhere = BillImage("a".repeat(64), 1_000, 100, 100)
        expect(ErrorCode.FORMAT) { save(expense(cash, listOf(elsewhere))) }
        expect(ErrorCode.FORMAT) { save(expense(cash, stored)) }
        expect(ErrorCode.FORMAT) { save(expense(cash, listOf(stored[0], stored[0]))) }
        // Figures come from the file, not from the form.
        val bill = save(expense(cash, listOf(stored[0].copy(width = 9, height = 9, byteSize = 9))))
        assertEquals(listOf(stored[0]), RoomBills(db).observeImages(bill).first())
    }

    @Test fun deleting_a_category_with_its_bills_lets_their_pictures_go() = runBlocking {
        val cash = cash()
        val p = store.put(picture(70))
        save(expense(cash, listOf(p), sub = "food.snacks"))
        store.unpin(listOf(p.key))
        val snacks = category("food.snacks")
        commands.execute(DeleteBillCategory(id(), snacks, db.bills().category(snacks)!!.revision, null,
            deleteBills = true, billTitle = "餐饮-零食"))
        assertFalse(store.has(p.key))
        assertTrue(db.bills().imageKeysInUse().isEmpty())
    }

    @Test fun backups_carry_which_bill_holds_which_picture_but_never_the_files() = runBlocking {
        val cash = cash()
        val (p1, p2) = listOf(1, 2).map { store.put(picture(it * 60)) }
        val bill = save(expense(cash, listOf(p2, p1)))
        val build = AppBuildInfo("valnook", "0.0.19", 1904, "20261114.1", "20261114.1", DATABASE_SCHEMA_VERSION)
        val backup = ByteArrayOutputStream()
        RoomPortabilityEngine(context, db, clock, build).createBackup(id(), backup) {}
        val entries = ZipInputStream(ByteArrayInputStream(backup.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry?.name }.toList()
        }
        assertTrue("data/bill_attachments.jsonl" in entries)
        assertTrue(entries.none { it.endsWith(".webp") })
        val target = database()
        try {
            val restored = RoomPortabilityEngine(context, target, clock, build)
            val staged = restored.prepareRestore(ByteArrayInputStream(backup.toByteArray()), "pictures.val_backup") {}
            try { restored.commitRestore(staged) {} } finally { restored.close(staged) }
            assertEquals(listOf(p2, p1), RoomBills(target).observeImages(bill).first())
            assertEquals(2, RoomBills(target).observeBill(bill).first()!!.imageCount)
            // The new database's own picture store has none of the files.
            assertFalse(BillImageStore(folder(), target, clock).has(p1.key))
        } finally { target.close() }
    }
}
