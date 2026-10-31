package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.AccountEntity
import dev.valnook.data.database.CashEntity
import dev.valnook.data.database.FxRateEntity
import dev.valnook.data.database.FxRateHistoryEntity
import dev.valnook.data.database.STATISTICS_RULE_VERSION
import dev.valnook.data.database.SettingsEntity
import dev.valnook.data.database.StatisticsCacheEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.model.StatisticsGranularity
import dev.valnook.domain.model.StatisticsMetric
import dev.valnook.domain.model.StatisticsPeriod
import dev.valnook.domain.model.StatisticsPoint
import dev.valnook.domain.model.StatisticsRequest
import dev.valnook.domain.repository.SaveFinancialSettings
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Rule 4: each past day at its own stored rate, else today's rate; a currency with neither is left out. */
@RunWith(AndroidJUnit4::class)
class StatisticsFxRuleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-05T04:00:00Z"), zone)   // October 5th, noon
    private fun day(d: Int) = LocalDate.of(2026, 10, d)
    private val start = day(1).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000
    private lateinit var db: ValnookDatabase
    private var account = 0L

    private fun inv(raw: String) = BigDecimal.ONE.divide(BigDecimal(raw), MathContext.DECIMAL128)
    private fun exact(expected: BigDecimal, actual: BigDecimal?) = assertEquals("$expected vs $actual", 0, expected.compareTo(actual))
    /** Cached values come back without trailing zeros, so compare by value and flag, not by scale. */
    private fun same(expected: List<StatisticsPoint>, actual: List<StatisticsPoint>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertEquals(e.date, a.date); assertEquals(e.complete, a.complete); exact(e.value!!, a.value)
        }
    }

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(start, day(1).toEpochDay()))
        db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        db.overview().insertRates(listOf(FxRateEntity("USD", "CNY", "7", 1)))
        account = db.accounts().insert_account(AccountEntity(name = "A", note = "", created_at_ms = start, updated_at_ms = start))
        cash("CNY", 100_000); cash("USD", 10_000); cash("EUR", 20_000)
    }

    @After fun close() = db.close()

    private suspend fun cash(currency: String, minor: Long, available: Boolean = true) {
        db.cash().insert_cash(CashEntity(account, currency, minor, 1, start, name = currency, created_at_ms = start,
            include_in_available_cash = available))
    }

    /** USD on the 1st, 2nd and 3rd (the 3rd carries the 2nd's value), EUR on the 3rd only; nothing on the 4th. */
    private suspend fun storeHistory() {
        fun row(quote: String, d: Int, source: Int, raw: String) =
            FxRateHistoryEntity("CNY", quote, day(d).toEpochDay(), day(source).toEpochDay(), raw, 1)
        db.fx().insertHistory(listOf(row("USD", 1, 1, "0.14"), row("USD", 2, 2, "0.15"), row("USD", 3, 2, "0.15"),
            row("EUR", 3, 3, "0.125")))
    }

    private suspend fun series(metric: StatisticsMetric = StatisticsMetric.TOTAL_ASSETS): List<StatisticsPoint> =
        RoomStatistics(db, clock).loadSeries(StatisticsRequest(metric, StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
            .points.take(5)

    @Test fun each_past_day_uses_its_own_stored_rate_else_today_s() = runBlocking {
        storeHistory()
        db.fx().setAutoEnabled(true)
        val cny = BigDecimal("1000")
        val usd = BigDecimal("100")
        val eurToday = BigDecimal("200").multiply(inv("0.125"))   // EUR has no current rate: its latest stored day
        val points = series()
        exact(cny + usd.multiply(inv("0.14")) + eurToday, points[0].value)
        exact(cny + usd.multiply(inv("0.15")) + eurToday, points[1].value)
        exact(cny + usd.multiply(inv("0.15")) + BigDecimal("200").multiply(inv("0.125")), points[2].value)  // weekend row
        exact(cny + usd.multiply(BigDecimal("7")) + eurToday, points[3].value)   // no row: today's (current) rate
        assertTrue(points.take(5).all { it.complete && it.reliable })
        // Today follows the home total.
        exact(AssetValuation.calculate(RoomOverview(db).snapshot()).total.amount, points[4].value)
        exact(cny + usd.multiply(BigDecimal("7")) + eurToday, points[4].value)
        // Read again from cache: same values.
        same(points, series())
    }

    @Test fun switching_off_and_editing_rates_only_changes_days_without_a_record() = runBlocking {
        storeHistory()
        db.fx().setAutoEnabled(true)
        val before = series()
        db.fx().setAutoEnabled(false)
        RoomSettings(db, clock).applyChange(SaveFinancialSettings(1, Currency.of("CNY"),
            listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.5")))))
        val after = series()
        exact(before[0].value!!, after[0].value)     // stored days keep their own rates
        exact(before[2].value!!, after[2].value)
        exact(before[3].value!! + BigDecimal("50"), after[3].value)   // 100 USD × (7.5 − 7)
    }

    @Test fun a_currency_without_any_rate_is_left_out_and_the_day_marked_incomplete() = runBlocking {
        storeHistory()
        db.fx().setAutoEnabled(true)
        val complete = series()
        cash("KWD", 1_000)
        db.statistics().invalidate(day(1).toEpochDay())
        val total = series()
        val cash = series(StatisticsMetric.AVAILABLE_CASH)
        val investment = series(StatisticsMetric.INVESTMENT_VALUE)
        (0..4).forEach { index ->
            exact(complete[index].value!!, total[index].value)
            assertFalse(total[index].complete)
            assertFalse(cash[index].complete)
            assertTrue(investment[index].complete)   // KWD is not an investment
        }
        // Reloaded from cache, the flag stays.
        same(total, series())
        val rows = db.statistics().cached("TOTAL_ASSETS", day(1).toEpochDay(), day(4).toEpochDay(),
            db.statistics().state()!!.source_revision, STATISTICS_RULE_VERSION)
        assertEquals(4, rows.size)
        assertTrue(rows.none { it.complete })
    }

    @Test fun completeness_is_tracked_per_metric() = runBlocking {
        storeHistory()
        db.fx().setAutoEnabled(true)
        cash("KWD", 1_000, available = false)
        assertTrue(series(StatisticsMetric.AVAILABLE_CASH).take(4).all { it.complete })
        assertTrue(series().take(4).none { it.complete })
    }

    @Test fun data_that_never_used_automatic_rates_values_every_day_as_before() = runBlocking {
        // Rule 3 valued every day at the current rates with 1:1 for missing ones: 1000 + 100 × 7 + 200 × 1.
        series().take(5).forEach {
            exact(BigDecimal("1900"), it.value)
            assertTrue(it.complete)
        }
    }

    @Test fun cache_from_rule_three_is_rebuilt_on_first_read() = runBlocking {
        storeHistory()
        db.fx().setAutoEnabled(true)
        val state = db.statistics().state()!!
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET rule_version=3,earliest_invalidated_epoch_day=NULL WHERE id=1")
        db.statistics().insertCache(listOf(StatisticsCacheEntity("TOTAL_ASSETS", day(1).toEpochDay(), "1", true,
            state.source_revision, 3, clock.millis())))
        val points = series()
        assertEquals(4, db.statistics().state()!!.rule_version)
        exact(BigDecimal("1000") + BigDecimal("100").multiply(inv("0.14")) + BigDecimal("200").multiply(inv("0.125")), points[0].value)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM statistics_cache WHERE rule_version<>4").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
    }
}
