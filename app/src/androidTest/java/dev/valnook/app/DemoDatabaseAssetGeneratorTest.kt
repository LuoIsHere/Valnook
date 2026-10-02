package dev.valnook.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.app.di.AppGraph
import dev.valnook.app.di.DemoDataSeeder
import dev.valnook.app.di.createDatabaseGraph
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DemoDatabaseAssetGeneratorTest {
    @Test
    fun exportRoomV6DemoDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(EXPORT_DATABASE_NAME)
        val clock = Clock.fixed(Instant.parse("2026-10-02T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
        val database = Room.databaseBuilder(context, ValnookDatabase::class.java, EXPORT_DATABASE_NAME)
            .addCallback(ValnookDatabase.seed)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val raw = createDatabaseGraph(database,clock)
            val graph = AppGraph("demo-asset-generator",raw.accounts,raw.cash,raw.deposits,
                raw.investments,raw.commands,raw.clock,raw.overview,raw.settings,raw.settingsWriter,
                raw.instruments,raw.cashPages,raw.depositPages)
            DemoDataSeeder(graph, clock).seed(AppSettings())
            val snapshot = graph.overview.snapshot()
            assertEquals(12, snapshot.accounts.size)
            assertEquals(48, snapshot.cash.size)
            assertEquals(60, snapshot.instruments.size)
            assertEquals(80, snapshot.positions.size)
        } finally {
            database.close()
        }

        val source = context.getDatabasePath(EXPORT_DATABASE_NAME)
        PlatformTestStorageRegistry.getInstance().openOutputFile(ASSET_FILE_NAME).use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        assertTrue(source.length() > 0)
    }

    private companion object {
        const val EXPORT_DATABASE_NAME = "valnook-demo-asset-export.db"
        const val ASSET_FILE_NAME = "valnook-demo-v6.db"
    }
}
