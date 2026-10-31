package dev.valnook.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.data.database.AccountEntity
import dev.valnook.data.database.CashEntity
import dev.valnook.data.database.FxRateEntity
import dev.valnook.data.database.SettingsEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.StatisticsGranularity
import dev.valnook.domain.model.StatisticsMetric
import dev.valnook.domain.model.StatisticsPeriod
import dev.valnook.domain.model.StatisticsRequest
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three years of daily rates: cold rebuild against the same data valued at today's rates, history reads and size growth. */
@LargeTest
class StatisticsFxPerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"), zone)
    private val today = LocalDate.now(clock)
    private val first = today.minusYears(3)
    private val foreign = listOf("USD", "HKD", "EUR", "GBP", "JPY", "SGD", "AUD", "CAD", "CHF")
    private fun raw(quote: Int, day: Long) = BigDecimal.valueOf(1000L + quote * 37L + day % 7, 4).toPlainString()

    private suspend fun rebuild(db: ValnookDatabase): Long {
        db.statistics().clearCache()
        db.statistics().invalidate(first.toEpochDay())
        val statistics = RoomStatistics(db, clock)
        val started = System.nanoTime()
        var month = YearMonth.from(first)
        while (month <= YearMonth.from(today)) {
            statistics.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
                StatisticsPeriod(StatisticsGranularity.DAILY, month.year, month.monthValue)))
            month = month.plusMonths(1)
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    private fun size(db: ValnookDatabase, name: String): Long {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        return context.getDatabasePath(name).length()
    }

    private suspend fun backupBytes(db: ValnookDatabase): Int {
        val output = ByteArrayOutputStream()
        RoomPortabilityEngine(context, db, clock, AppBuildInfo("valnook", "0.0.18", 18, "20261031.1", "20261031.1", 22))
            .createBackup(UUID.randomUUID().toString(), output) {}
        return output.size()
    }

    @Test fun three_years_of_daily_rates_for_ten_currencies() = runBlocking {
        val name = "fx-performance-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, ValnookDatabase::class.java, name).addCallback(ValnookDatabase.seed).build()
        try {
            val start = first.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
            db.openHelper.writableDatabase.execSQL(
                "UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
                arrayOf(start, first.toEpochDay()))
            db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
            db.overview().insertRates(foreign.mapIndexed { index, code -> FxRateEntity(code, "CNY", "${index + 2}", 1) })
            val account = db.accounts().insert_account(AccountEntity(name = "A", note = "", created_at_ms = start, updated_at_ms = start))
            (listOf("CNY") + foreign).forEach { code ->
                db.cash().insert_cash(CashEntity(account, code, 1_000_000, 1, start, name = code, created_at_ms = start))
            }
            val todayRates = rebuild(db)
            val sizeBefore = size(db, name)
            val backupBefore = backupBytes(db)

            // Every supported quote, every day: about 58 000 rows.
            val quotes = Currency.supported.map { it.code } - "CNY"
            db.withTransaction {
                val insert = db.openHelper.writableDatabase.compileStatement(
                    "INSERT INTO fx_rate_history VALUES ('CNY',?,?,?,?,0)")
                quotes.forEachIndexed { index, quote ->
                    (first.toEpochDay()..today.toEpochDay()).forEach { day ->
                        insert.clearBindings()
                        insert.bindString(1, quote); insert.bindLong(2, day); insert.bindLong(3, day)
                        insert.bindString(4, raw(index, day)); insert.executeInsert()
                    }
                }
            }
            db.fx().setAutoEnabled(true)
            val rows = quotes.size * (today.toEpochDay() - first.toEpochDay() + 1)
            val dailyRates = rebuild(db)
            val reads = (1..5).map {
                val started = System.nanoTime()
                assertEquals(rows.toInt(), db.fx().historyFor("CNY", quotes).size)
                (System.nanoTime() - started) / 1_000_000
            }
            val sizeAfter = size(db, name)
            val backupAfter = backupBytes(db)

            // Spot check: a past day values each foreign balance at that day's stored rate.
            val sample = first.plusDays(400)
            val point = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
                StatisticsPeriod(StatisticsGranularity.DAILY, sample.year, sample.monthValue))).points
                .single { it.date == sample }
            val expected = foreign.fold(BigDecimal("10000")) { total, code ->
                total + BigDecimal.valueOf(1_000_000, Currency.of(code).fraction_digits)
                    .multiply(BigDecimal.ONE.divide(BigDecimal(raw(quotes.indexOf(code), sample.toEpochDay())), MathContext.DECIMAL128))
            }
            assertEquals(0, expected.compareTo(point.value))

            val result = JSONObject().put("days", today.toEpochDay() - first.toEpochDay() + 1).put("historyRows", rows)
                .put("rebuildTodayRatesMs", todayRates).put("rebuildDailyRatesMs", dailyRates)
                .put("readAllHistoryMs", reads.toString()).put("databaseBytesBefore", sizeBefore)
                .put("databaseBytesAfter", sizeAfter).put("backupBytesBefore", backupBefore).put("backupBytesAfter", backupAfter)
            Log.i("FxPerformance", result.toString())
            PlatformTestStorageRegistry.getInstance().openOutputFile("fx-performance.json").use {
                it.write(result.toString(2).toByteArray())
            }
            // Generous bounds; the numbers above are what the change log records.
            assertTrue(result.toString(), dailyRates < 20_000)
            assertTrue(result.toString(), reads.min() < 5_000)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
