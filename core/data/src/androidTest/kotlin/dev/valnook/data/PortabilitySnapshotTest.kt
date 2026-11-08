package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import dev.valnook.data.database.DATABASE_SCHEMA_VERSION
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/**
 * Backups and workbooks are written from a copy of the database taken at one moment, so saving is never held up while
 * the files are written. A file database in WAL mode, as the app opens it, takes the copy on a connection of its own.
 */
class PortabilitySnapshotTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.systemUTC()
    private val build = AppBuildInfo("valnook", "0.0.18", 18, "20261108.1", "20261108.1", DATABASE_SCHEMA_VERSION)
    private val name = "snapshot-test-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<ValnookDatabase>()

    @After fun close() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    private fun fileDatabase() = Room.databaseBuilder(context, ValnookDatabase::class.java, name)
        .addCallback(ValnookDatabase.seed).addCallback(ValnookDatabase.defaults)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build().also { opened += it }

    private suspend fun account(commands: RoomFinancialCommands, name: String) = commands.execute(SaveAccount(
        UUID.randomUUID().toString(), null, null, name, "", emptyList(), AccountIconChange(AccountIcon(value = "savings"))))

    private fun names(db: ValnookDatabase) = runBlocking { RoomOverview(db).snapshot().accounts.map { it.name }.sorted() }

    private fun leftOverCopies() = File(context.cacheDir, "valnook-portability").listFiles().orEmpty()
        .filter { it.name.startsWith("snapshot-") }

    @Test fun saving_goes_on_while_a_snapshot_is_read_and_the_snapshot_keeps_its_moment() = runBlocking {
        val db = fileDatabase()
        val commands = RoomFinancialCommands(db, clock)
        account(commands, "First")
        val engine = RoomPortabilityEngine(context, db, clock, build)
        engine.readSnapshot { copy ->
            assertFalse("The live database holds no transaction while the copy is read", db.inTransaction())
            // Another thread saves meanwhile and is not held up.
            withTimeout(5_000) { withContext(Dispatchers.IO) { account(commands, "Second") } }
            assertEquals(listOf("First"), names(copy))
        }
        assertEquals(listOf("First", "Second"), names(db))
        assertTrue(leftOverCopies().isEmpty())
    }

    @Test fun a_backup_from_the_snapshot_restores_the_same_data() = runBlocking {
        val db = fileDatabase()
        val commands = RoomFinancialCommands(db, clock)
        account(commands, "Bank")
        account(commands, "Broker")
        val archive = ByteArrayOutputStream()
        RoomPortabilityEngine(context, db, clock, build).createBackup(UUID.randomUUID().toString(), archive) {}
        val target = ValnookDatabase.inMemory(context).also { opened += it }
        val targetEngine = RoomPortabilityEngine(context, target, clock, build)
        val staged = targetEngine.prepareRestore(ByteArrayInputStream(archive.toByteArray()), "snapshot.val_backup") {}
        try { targetEngine.commitRestore(staged) {} } finally { targetEngine.close(staged) }
        assertEquals(RoomOverview(db).snapshot().accounts, RoomOverview(target).snapshot().accounts)
        assertTrue(leftOverCopies().isEmpty())
    }

    @Test fun a_workbook_is_written_from_the_snapshot() = runBlocking {
        val db = fileDatabase()
        account(RoomFinancialCommands(db, clock), "Bank")
        val output = ByteArrayOutputStream()
        val result = RoomPortabilityEngine(context, db, clock, build)
            .exportWorkbook(UUID.randomUUID().toString(), AppLanguage.ENGLISH, false, output) {}
        assertTrue(result.byteCount > 0)
        assertEquals(result.byteCount, output.size().toLong())
        assertTrue(leftOverCopies().isEmpty())
    }
}
