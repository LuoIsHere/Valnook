package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.AccountEntity
import dev.valnook.data.database.CashEntity
import dev.valnook.data.database.FxRateEntity
import dev.valnook.data.database.FxRateHistoryEntity
import dev.valnook.data.database.SettingsEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.MissingAmount
import dev.valnook.domain.model.MissingKind
import dev.valnook.domain.model.StatisticsGranularity
import dev.valnook.domain.model.StatisticsMetric
import dev.valnook.domain.model.StatisticsPeriod
import dev.valnook.domain.model.StatisticsRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Home, statistics and Excel convert through the same lookup: current rate, latest stored day, else missing. */
@RunWith(AndroidJUnit4::class)
class FxRateLookupDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-02T04:00:00Z"), zone)
    private val octoberFirst = LocalDate.of(2026, 10, 1)
    private lateinit var db: ValnookDatabase
    private var account = 0L

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        val start = octoberFirst.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
        db.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(start, octoberFirst.toEpochDay()))
        db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        db.overview().insertRates(listOf(FxRateEntity("USD", "CNY", "7.1", 1), FxRateEntity("CNY", "USD", "0.14", 1)))
        account = db.accounts().insert_account(AccountEntity(name = "A", note = "", created_at_ms = start, updated_at_ms = start))
        cash("CNY", 100_000, start); cash("USD", 10_000, start); cash("EUR", 20_000, start)
        // EUR has no current rate; its latest stored day is October 1st. USD's stored day is older than its current rate.
        db.fx().insertHistory(listOf(
            FxRateHistoryEntity("CNY", "EUR", octoberFirst.minusDays(1).toEpochDay(), octoberFirst.minusDays(1).toEpochDay(), "0.125", 1),
            FxRateHistoryEntity("CNY", "EUR", octoberFirst.toEpochDay(), octoberFirst.minusDays(1).toEpochDay(), "0.128", 1),
            FxRateHistoryEntity("CNY", "USD", octoberFirst.toEpochDay(), octoberFirst.toEpochDay(), "0.15", 1)))
    }

    @After fun close() = db.close()

    private suspend fun cash(currency: String, minor: Long, at: Long) {
        db.cash().insert_cash(CashEntity(account, currency, minor, 1, at, name = currency, created_at_ms = at))
    }
    private fun decimal(expected: String, actual: BigDecimal?) = assertEquals(expected, 0, BigDecimal(expected).compareTo(actual))
    /** October 1st has its own stored rates: 1000 CNY + 100 USD / 0.15 + 200 EUR / 0.128. */
    private val octoberFirstValue = BigDecimal("1000") +
        BigDecimal("100").multiply(BigDecimal.ONE.divide(BigDecimal("0.15"), java.math.MathContext.DECIMAL128)) + BigDecimal("1562.5")
    private fun exact(expected: BigDecimal, actual: BigDecimal?) = assertEquals("$expected", 0, expected.compareTo(actual))
    private suspend fun home() = AssetValuation.calculate(RoomOverview(db).snapshot()).total
    private suspend fun series() = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
        StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))

    @Test fun snapshot_carries_the_switch_and_each_currency_s_latest_day() = runBlocking {
        db.fx().setAutoEnabled(true)
        val fx = RoomOverview(db).snapshot().fx
        assertTrue(fx.autoEnabled)
        assertEquals(setOf("EUR", "USD"), fx.latest.keys)
        assertEquals(BigDecimal("0.128"), fx.latest.getValue("EUR").raw)
        assertEquals(octoberFirst.minusDays(1), fx.latest.getValue("EUR").sourceDate)
        assertEquals(fx, RoomOverview(db).observeFxContext().first())
    }

    @Test fun automatic_totals_use_the_stored_day_and_statistics_agree_with_home() = runBlocking {
        db.fx().setAutoEnabled(true)
        // 1000 CNY + 100 USD × 7.1 (current) + 200 EUR × 7.8125 (1 / 0.128 stored).
        decimal("3272.5", home().amount)
        assertTrue(home().complete)
        decimal("3272.5", RoomStatistics(db, clock).loadCurrent().totalAssets)
        assertTrue(RoomStatistics(db, clock).loadCurrent().complete)
        val points = series().points
        exact(octoberFirstValue, points[0].value)   // a past day uses its own stored rates
        decimal("3272.5", points[1].value)        // today follows home
    }

    @Test fun automatic_mode_leaves_out_a_currency_without_any_rate() = runBlocking {
        db.fx().setAutoEnabled(true)
        cash("KWD", 1_000, octoberFirst.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000)
        val total = home()
        decimal("3272.5", total.amount)
        assertEquals(setOf(MissingAmount(MissingKind.EXCHANGE_RATE, "KWD")), total.missing)
        // The summary follows home and the chart: the amount without KWD, marked incomplete.
        val current = RoomStatistics(db, clock).loadCurrent()
        decimal("3272.5", current.totalAssets)
        assertFalse(current.complete)
        val past = series().points[0]
        exact(octoberFirstValue, past.value)
        assertFalse(past.complete)
    }

    @Test fun manual_mode_also_leaves_out_a_currency_without_any_rate() = runBlocking {
        cash("KWD", 1_000, octoberFirst.atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000)
        decimal("3272.5", home().amount)
        assertEquals(setOf(MissingAmount(MissingKind.EXCHANGE_RATE, "KWD")), home().missing)
        assertFalse(RoomStatistics(db, clock).loadCurrent().complete)
        val past = series().points[0]
        exact(octoberFirstValue, past.value)   // stored days count in manual mode too; KWD is left out
        assertFalse(past.complete)
    }

    private suspend fun summarySheet(): String {
        val engine = RoomPortabilityEngine(context, db, clock, AppBuildInfo("valnook", "0.0.18", 18, "t", "t", 22))
        val output = ByteArrayOutputStream()
        engine.exportWorkbook(UUID.randomUUID().toString(), AppLanguage.ZH_HANS, false, output) {}
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "xl/worksheets/sheet1.xml") return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("No summary sheet")
    }

    /** Text of each summary row, cell by cell. */
    private fun rows(sheet: String) = Regex("<row[^>]*>(.*?)</row>").findAll(sheet).map { row ->
        Regex("<c [^>]*>(?:<is><t[^>]*>(.*?)</t></is>|<v>(.*?)</v>)?</c>").findAll(row.groupValues[1])
            .map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
    }.toList()

    @Test fun excel_rates_name_their_source_and_data_date() = runBlocking {
        db.fx().setAutoEnabled(true)
        var rows = rows(summarySheet())
        assertTrue(rows.contains(listOf("报表格式", "3")))
        assertTrue(rows.contains(listOf("缺失汇率口径", "没有可用汇率的币种不计入总额")))
        assertTrue(rows.contains(listOf("项目", "货币对", "汇率", "来源", "数据日期")))
        assertTrue(rows.toString(), rows.contains(listOf("汇率", "USD/CNY", "7.1", "自动", octoberFirst.toString())))
        assertTrue(rows.contains(listOf("汇率", "CNY/USD", "0.14", "自动", "")))   // another base has no data date

        db.fx().setAutoEnabled(false)
        rows = rows(summarySheet())
        assertTrue(rows.contains(listOf("缺失汇率口径", "没有可用汇率的币种不计入总额")))
        assertTrue(rows.contains(listOf("汇率", "USD/CNY", "7.1", "手动", "")))
        assertFalse(rows.any { it.firstOrNull() == "手动汇率" })
    }
}
