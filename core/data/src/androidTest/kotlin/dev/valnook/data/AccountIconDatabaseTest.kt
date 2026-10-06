package dev.valnook.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.*
import dev.valnook.data.portability.*
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.portability.PortabilityException
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.UUID

class AccountIconDatabaseTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ValnookDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val databases = mutableListOf<ValnookDatabase>()
    private val clock = Clock.systemUTC()
    @After fun close() { databases.forEach { it.close() } }
    private fun db() = ValnookDatabase.inMemory(context).also { databases += it }
    private fun operation() = UUID.randomUUID().toString()
    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it); bitmap.recycle() }.toByteArray()
    }
    private fun count(db: ValnookDatabase) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM account_icon_images")
        .use { it.moveToFirst(); it.getInt(0) }
    private fun engine(db: ValnookDatabase) = RoomPortabilityEngine(context, db, clock,
        AppBuildInfo("valnook", "0.08", 8, "20261005.2", "icons-test", 13))

    @Test fun migration_preserves_accounts_cash_revisions_and_adds_default_icon() {
        val name = "icon-migration-${operation()}.db"
        try {
            helper.createDatabase(name, 12).apply {
                execSQL("INSERT INTO currencies VALUES ('CNY',2)")
                execSQL("INSERT INTO savings_accounts VALUES (7,'Bank','note',10,20,4,2)")
                execSQL("INSERT INTO cash_accounts VALUES (7,'CNY',-12345,3,20,2,'Cash','',1,10,0)")
                close()
            }
            helper.runMigrationsAndValidate(name, 13, true, MIGRATION_12_13).apply {
                query("SELECT name,revision,display_order,icon_type,icon_value FROM savings_accounts WHERE id=7").use {
                    assertTrue(it.moveToFirst()); assertEquals("Bank", it.getString(0)); assertEquals(4L, it.getLong(1))
                    assertEquals(2L, it.getLong(2)); assertEquals("SYMBOL", it.getString(3)); assertEquals("account_balance", it.getString(4))
                }
                query("SELECT balance_minor,revision FROM cash_accounts WHERE id=2").use {
                    assertTrue(it.moveToFirst()); assertEquals(-12345L, it.getLong(0)); assertEquals(3L, it.getLong(1))
                }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun old_save_requests_preserve_image_and_retries_deduplicate() = runBlocking {
        val db = db(); val commands = RoomFinancialCommands(db, clock)
        val bytes = image(); val key = AccountIconImages.digest(bytes)
        val create = SaveAccount(operation(), null, null, "Bank", "", emptyList(),
            AccountIconChange(AccountIcon(AccountIconType.IMAGE, key), bytes))
        val id = commands.execute(create).id
        assertEquals(id, commands.execute(create).id)
        assertEquals(1, count(db))
        commands.execute(SaveAccount(operation(), id, 1, "Renamed via old client", "", emptyList()))
        val account = RoomOverview(db).snapshot().accounts.single()
        assertEquals(key, account.icon.imageKey)
        assertArrayEquals(bytes, RoomOverview(db).accountIconImage(key))
        try {
            commands.execute(create.copy(iconChange = AccountIconChange(AccountIcon(value = "wallet"))))
            fail("A reused operation ID with a different icon must conflict")
        } catch (error: DomainException) { assertEquals(ErrorCode.OPERATION_CONFLICT, error.code) }
        commands.execute(SaveAccount(operation(), id, account.revision, account.name, "", emptyList(),
            AccountIconChange(AccountIcon())))
        assertEquals(AccountIcon(), RoomOverview(db).snapshot().accounts.single().icon)
    }

    @Test fun image_and_account_roll_back_together_and_invalid_image_is_rejected() = runBlocking {
        val db = db(); val bytes = image()
        val command = SaveAccount(operation(), null, null, "Bank", "", emptyList(),
            AccountIconChange(AccountIcon(AccountIconType.IMAGE, AccountIconImages.digest(bytes)), bytes))
        val failing = RoomFinancialCommands(db, clock) { if (it == TransactionPoint.AFTER_BUSINESS) error("Injected failure") }
        try { failing.execute(command); fail("Expected rollback") } catch (_: IllegalStateException) { }
        assertEquals(0, count(db)); assertTrue(RoomOverview(db).snapshot().accounts.isEmpty())
        try {
            RoomFinancialCommands(db, clock).execute(command.copy(operation_id = operation(),
                iconChange = AccountIconChange(AccountIcon(AccountIconType.IMAGE, "bad"), bytes)))
            fail("Invalid image must be rejected")
        } catch (error: DomainException) { assertEquals(ErrorCode.FORMAT, error.code) }
        assertEquals(0, count(db)); assertTrue(RoomOverview(db).snapshot().accounts.isEmpty())
    }

    @Test fun backup_restores_images_and_symbols_and_deduplicates_shared_images() = runBlocking {
        val source = db(); val commands = RoomFinancialCommands(source, clock)
        val bytes = image(); val key = AccountIconImages.digest(bytes)
        for (name in listOf("Bank A", "Bank B")) commands.execute(SaveAccount(operation(), null, null, name, "", emptyList(),
            AccountIconChange(AccountIcon(AccountIconType.IMAGE, key), bytes)))
        commands.execute(SaveAccount(operation(), null, null, "Savings", "", emptyList(), AccountIconChange(AccountIcon(value = "savings"))))
        assertEquals(1, count(source))
        val archive = ByteArrayOutputStream(); engine(source).createBackup(operation(), archive) {}
        val target = db(); val targetEngine = engine(target)
        val staged = targetEngine.prepareRestore(ByteArrayInputStream(archive.toByteArray()), "icons.val_backup") {}
        try { targetEngine.commitRestore(staged) {} } finally { targetEngine.close(staged) }
        assertEquals(RoomOverview(source).snapshot().accounts, RoomOverview(target).snapshot().accounts)
        assertArrayEquals(bytes, RoomOverview(target).accountIconImage(key)); assertEquals(1, count(target))
        assertNull(RoomOverview(db()).accountIconImage(key))
        // Exercise the existing user-requested reset only on this synthetic in-memory ledger.
        dev.valnook.data.repository.RoomDataMaintenance(target).clearBusinessData()
        assertEquals(0, count(target))
    }

    @Test fun restore_preflight_rejects_missing_and_corrupt_image_assets() = runBlocking {
        for (corrupt in listOf(false, true)) {
            val source = db(); val bytes = image(); val key = AccountIconImages.digest(bytes)
            RoomFinancialCommands(source, clock).execute(SaveAccount(operation(), null, null, "Bank", "", emptyList(),
                AccountIconChange(AccountIcon(AccountIconType.IMAGE, key), bytes)))
            val archive = ByteArrayOutputStream(); engine(source).createBackup(operation(), archive) {}
            val files = linkedMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(ByteArrayInputStream(archive.toByteArray())).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
            }
            val path = if (corrupt) "data/account_icon_images.jsonl" else "data/accounts.jsonl"
            files[path] = (org.json.JSONObject(files.getValue(path).toString(Charsets.UTF_8).trim()).apply {
                if (corrupt) put("data", "AQID") else put("icon_value", "a".repeat(64))
            }.toString() + "\n").toByteArray()
            val manifest = org.json.JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
            val rows = manifest.getJSONArray("files")
            repeat(rows.length()) { index -> rows.getJSONObject(index).let { row ->
                if (row.getString("path") == path) {
                    row.put("uncompressedBytes", files.getValue(path).size)
                    row.put("sha256", AccountIconImages.digest(files.getValue(path)))
                }
            } }
            files["manifest.json"] = manifest.toString().toByteArray()
            val altered = ByteArrayOutputStream()
            java.util.zip.ZipOutputStream(altered).use { zip -> files.forEach { (name, data) ->
                zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(data); zip.closeEntry()
            } }
            val targetEngine = engine(db())
            try {
                val staged = targetEngine.prepareRestore(ByteArrayInputStream(altered.toByteArray()), "bad-icons.val_backup") {}
                targetEngine.close(staged); fail("Invalid icon assets must fail before restore commit")
            } catch (error: PortabilityException) {
                assertEquals(dev.valnook.domain.portability.PortabilityErrorCode.INVALID_DATA, error.errorCode)
            }
        }
    }
}
