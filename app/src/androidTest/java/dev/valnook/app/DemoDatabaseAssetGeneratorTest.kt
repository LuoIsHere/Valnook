package dev.valnook.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.app.di.AppGraph
import dev.valnook.app.di.DemoDataSeeder
import dev.valnook.app.di.createDatabaseGraph
import dev.valnook.app.di.currentBuildInfo
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
import java.time.LocalDate
import java.time.YearMonth
import dev.valnook.data.database.InstrumentPriceEntity

@RunWith(AndroidJUnit4::class)
class DemoDatabaseAssetGeneratorTest {
    @Test
    fun exportRoomV9DemoDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(EXPORT_DATABASE_NAME)
        val clock = Clock.fixed(Instant.parse("2026-10-02T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
        val database = Room.databaseBuilder(context, ValnookDatabase::class.java, EXPORT_DATABASE_NAME)
            .addCallback(ValnookDatabase.seed)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val raw = createDatabaseGraph(context,database,clock,currentBuildInfo())
            val graph = AppGraph("demo-asset-generator",raw.accounts,raw.cash,raw.deposits,
                raw.investments,raw.commands,raw.clock,raw.overview,
                dev.valnook.data.repository.RoomStatistics(database, clock),raw.settings,raw.settingsWriter,
                raw.instruments,raw.cashPages,raw.depositPages)
            DemoDataSeeder(graph, clock).seed(AppSettings())
            enrichHistoricalSources(database, clock)
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

    private suspend fun enrichHistoricalSources(database: ValnookDatabase, clock: Clock) {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val baseline = LocalDate.of(today.year - 1, 1, 1)
        val baselineMs = baseline.atStartOfDay(zone).toInstant().toEpochMilli()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("UPDATE cash_accounts SET created_at_ms=?,updated_at_ms=MAX(updated_at_ms,?)",
            arrayOf(baselineMs, baselineMs))
        sql.execSQL("""UPDATE cash_entries SET occurred_at_ms=? + (id % 55) * 86400000
            WHERE source_kind='CASH_SET'""", arrayOf(baselineMs))
        sql.execSQL("""UPDATE term_deposits SET created_at_ms=start_epoch_day*86400000,
            closed_at_ms=CASE WHEN status='CLOSED' THEN end_epoch_day*86400000+43200000 ELSE NULL END""")
        database.statistics().clearCache()
        database.statistics().clearBaselineItems()
        sql.execSQL("UPDATE statistics_state SET source_revision=source_revision+1,baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(baselineMs, baseline.toEpochDay()))
        insertDemoLabels(database)

        val instruments = database.overview().allInstruments().map { it.instrument }
        instruments.forEachIndexed { index, instrument ->
            var month = YearMonth.from(baseline)
            val finalMonth = YearMonth.from(today).minusMonths(1)
            var monthIndex = 0
            while (month <= finalMonth) {
                val factorBasisPoints = 8200 + ((index * 131 + monthIndex * 73) % 3300)
                val price = instrument.current_price_e5 * factorBasisPoints / 10_000L
                val effective = month.atEndOfMonth().atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
                database.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument.id,
                    price_e5 = price.coerceAtLeast(1), currency_code = instrument.currency_code,
                    effective_at_ms = effective, created_at_ms = clock.millis()))
                month = month.plusMonths(1)
                monthIndex++
            }
            (1..today.dayOfMonth).forEach { day ->
                val factorBasisPoints = 9700 + ((index * 19 + day * 41) % 650)
                val price = instrument.current_price_e5 * factorBasisPoints / 10_000L
                val effective = today.withDayOfMonth(day).atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
                database.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument.id,
                    price_e5 = if (day == today.dayOfMonth) instrument.current_price_e5 else price.coerceAtLeast(1),
                    currency_code = instrument.currency_code,
                    effective_at_ms = effective.coerceAtMost(clock.millis()), created_at_ms = clock.millis()))
            }
        }
    }

    private suspend fun insertDemoLabels(database: ValnookDatabase) {
        val sql = database.openHelper.writableDatabase
        fun label(kind: String, id: Long, field: String, zh: String, en: String) {
            sql.execSQL("INSERT OR REPLACE INTO demo_labels(entity_kind,entity_id,field_name,zh_hans,english) VALUES (?,?,?,?,?)",
                arrayOf(kind, id, field, zh, en))
        }
        val accountEnglish = listOf("China Merchants Securities", "Huatai Securities", "CITIC Securities",
            "Guotai Junan Securities", "GF Securities", "CICC Wealth", "Futu Securities",
            "BOCI Securities", "HSBC Securities", "Interactive Brokers", "Charles Schwab", "Tiger Brokers")
        database.overview().allAccounts().forEachIndexed { index, account ->
            label("ACCOUNT", account.id, "NAME", account.name, accountEnglish[index])
            label("ACCOUNT", account.id, "NOTE", account.note, "Investment account ${index + 1}")
        }
        database.overview().allCash().forEach { cash ->
            val english = when (cash.name) {
                "人民币日常资金" -> "CNY daily funds"
                "美元交易资金" -> "USD trading funds"
                "美元备用资金" -> "USD reserve funds"
                "港币现金" -> "HKD cash"
                else -> cash.currency_code
            }
            label("CASH", cash.id, "NAME", cash.name, english)
        }
        val cursor = sql.query("SELECT id,name FROM asset_types ORDER BY id")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)
                val english = when (name) { "A股股票" -> "China A-shares"; "港股股票" -> "Hong Kong stocks"; else -> "US stocks" }
                label("TYPE", id, "NAME", name, english)
            }
        }
        database.overview().allInstruments().forEach { row ->
            val instrument = row.instrument
            val english = if (instrument.currency_code == "USD") instrument.name else
                "${instrument.symbol} ${when (instrument.currency_code) { "CNY" -> "A-share"; else -> "HK stock" }}"
            label("INSTRUMENT", instrument.id, "NAME", instrument.name, english)
        }
    }

    private companion object {
        const val EXPORT_DATABASE_NAME = "valnook-demo-asset-export.db"
        const val ASSET_FILE_NAME = "valnook-demo-v9.db"
    }
}
