package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.StatisticsCacheEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.fx.FrankfurterClient
import dev.valnook.data.fx.FrankfurterResponse
import dev.valnook.data.fx.FrankfurterTransport
import dev.valnook.data.fx.FxSyncCoordinator
import dev.valnook.data.fx.FxSyncOutcome
import dev.valnook.data.fx.FxWriteGate
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.fx.FxSyncError
import dev.valnook.domain.fx.FxSyncTrigger
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveFinancialSettings
import java.io.IOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FxSyncCoordinatorTest {
    private class MutableClock(var now: Instant, var zoneId: ZoneId) : Clock() {
        override fun getZone(): ZoneId = zoneId
        override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)
        override fun instant(): Instant = now
    }

    /** Answers like Frankfurter v2: the nearest earlier value for one date, only published days for a range. */
    private class FakeServer : FrankfurterTransport {
        val urls = mutableListOf<String>()
        val published = mutableMapOf<String, MutableMap<LocalDate, String>>()
        var failure: Exception? = null
        var status = 200
        var onRequest: suspend () -> Unit = {}
        /** The real source also sends a quote's last earlier value when nothing was published on a range's first day. */
        var carryIntoRanges = false

        override suspend fun get(url: String): FrankfurterResponse {
            urls += url
            onRequest()
            failure?.let { throw it }
            if (status != 200) return FrankfurterResponse(status, ByteArray(0))
            val query = url.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val base = query.getValue("base")
            val json = query.getValue("quotes").split(',').flatMap { quote ->
                val days = published[quote] ?: return@flatMap emptyList<String>()
                val chosen = query["date"]?.let { date ->
                    listOfNotNull(days.filterKeys { it <= LocalDate.parse(date) }.maxByOrNull { it.key })
                } ?: run {
                    val from = LocalDate.parse(query["from"])
                    val inRange = days.entries.filter { it.key >= from && it.key <= LocalDate.parse(query["to"]) }
                    val earlier = days.entries.filter { it.key < from }.maxByOrNull { it.key }
                    if (carryIntoRanges && from !in days && earlier != null) listOf(earlier) + inRange else inRange
                }
                chosen.map { """{"date":"${it.key}","base":"$base","quote":"$quote","rate":${it.value}}""" }
            }.joinToString(",", "[", "]")
            return FrankfurterResponse(200, json.toByteArray())
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private val clock = MutableClock(Instant.parse("2026-10-28T04:00:00Z"), shanghai)
    private val server = FakeServer()
    private lateinit var db: ValnookDatabase
    private lateinit var sync: FxSyncCoordinator

    private fun date(value: String) = LocalDate.parse(value)
    private fun day(value: String) = date(value).toEpochDay()
    private fun inverse(raw: String) = BigDecimal.ONE.divide(BigDecimal(raw), 12, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        RoomSettings(db, clock).applyChange(SaveFinancialSettings(0, Currency.of("CNY"), listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.1")),
            FxRate(Currency.of("EUR"), Currency.of("CNY"), BigDecimal("8")),
            FxRate(Currency.of("CNY"), Currency.of("USD"), BigDecimal("0.14")))))
        cash("USD"); cash("JPY")
        val baseline = date("2026-10-25").atStartOfDay(shanghai).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=NULL", arrayOf(baseline))
        db.fx().setAutoEnabled(true)
        // USD skips the 26th, JPY starts on the 27th, KWD never appears.
        (20..31).forEach { d ->
            val day = date("2026-10-%02d".format(d))
            if (d != 26) server.published.getOrPut("USD") { mutableMapOf() }[day] = "0.150%02d".format(d)
            server.published.getOrPut("EUR") { mutableMapOf() }[day] = "0.130%02d".format(d)
            server.published.getOrPut("GBP") { mutableMapOf() }[day] = "0.110%02d".format(d)
            if (d >= 27) server.published.getOrPut("JPY") { mutableMapOf() }[day] = "23.%02d".format(d)
        }
        sync = FxSyncCoordinator(db, FrankfurterClient(server), clock)
    }

    @After fun close() = db.close()

    private suspend fun cash(currency: String) = RoomFinancialCommands(db, clock).execute(SaveAccount(testOperationId(), null, null,
        "$currency cash", "", listOf(CashBalanceChange(currency, 1_000, null, name = "$currency cash"))))

    private suspend fun rates() = db.overview().rates().associate { (it.source_currency to it.target_currency) to it.rate }
    private suspend fun history(quote: String) = db.fx().history("CNY").filter { it.quote_currency == quote }
        .map { Triple(LocalDate.ofEpochDay(it.local_day).toString(), LocalDate.ofEpochDay(it.source_date).toString(), it.rate) }
    private fun queries() = server.urls.map { it.substringAfter("quotes=").substringAfter('&') }

    @Test fun first_sync_fills_from_the_baseline_and_replaces_rates_in_use_or_listed() = runBlocking {
        db.statistics().insertCache(listOf(StatisticsCacheEntity("NET_WORTH", day("2026-10-26"), "1", true, 1, 3, 1)))
        val revision = db.overview().settings()!!.revision
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        // One single-day request for the first day, one range for the rest.
        assertEquals(listOf("date=2026-10-25", "from=2026-10-26&to=2026-10-28"), queries())
        assertTrue(server.urls.all { it.contains("quotes=AED,ARS,AUD,") && !it.contains("CNY,") })
        assertEquals(listOf(Triple("2026-10-25", "2026-10-25", "0.15025"), Triple("2026-10-26", "2026-10-25", "0.15025"),
            Triple("2026-10-27", "2026-10-27", "0.15027"), Triple("2026-10-28", "2026-10-28", "0.15028")), history("USD"))
        assertEquals(listOf("2026-10-27", "2026-10-28"), history("JPY").map { it.first })
        assertEquals(4, history("GBP").size)
        assertTrue(history("KWD").isEmpty())

        val rates = rates()
        assertEquals(inverse("0.15028"), rates[("USD" to "CNY")])
        assertEquals(inverse("0.13028"), rates[("EUR" to "CNY")])   // listed, not in use
        assertEquals(inverse("23.28"), rates[("JPY" to "CNY")])     // in use, not listed before
        assertNull(rates[("GBP" to "CNY")])                         // neither
        assertEquals("0.14", rates[("CNY" to "USD")])               // another base's group stays
        assertEquals("0.042955326460", BigDecimal.ONE.divide(BigDecimal("23.28"), 12, RoundingMode.HALF_EVEN).toPlainString())

        val state = db.fx().localState()!!
        assertEquals(day("2026-10-28"), state.last_success_day)
        assertEquals(day("2026-10-28"), state.data_date)
        assertNull(state.last_error)
        assertEquals(revision + 1, db.overview().settings()!!.revision)
        val event = db.openHelper.readableDatabase.query("SELECT entity_kind,source FROM audit_events ORDER BY recorded_at_ms DESC,rowid DESC LIMIT 1")
            .use { it.moveToFirst(); it.getString(0) to it.getString(1) }
        assertEquals("FINANCIAL_SETTINGS" to "SYSTEM", event)
        val sources = db.openHelper.readableDatabase.query("SELECT source FROM audit_events WHERE entity_kind='FINANCIAL_SETTINGS' ORDER BY recorded_at_ms,rowid")
            .use { generateSequence { if (it.moveToNext()) it.getString(0) else null }.toList() }
        assertEquals(listOf("USER", "SYSTEM"), sources)   // the user's own save stays a user event
        // Statistics still use today's rates for every day, so the change reaches back to the baseline.
        assertEquals(day("2026-10-25"), db.statistics().state()!!.earliest_invalidated_epoch_day)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM statistics_cache").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        assertEquals(emptyList<String>(), sync.observeStatus().first().missingCurrencies)
        assertEquals(date("2026-10-28"), sync.observeStatus().first().lastSuccessDay)
        assertNull(sync.observeNotice().first())
    }

    @Test fun start_runs_once_a_day_and_later_syncs_fill_every_missed_day() = runBlocking {
        sync.sync(FxSyncTrigger.START)
        server.urls.clear()
        assertEquals(FxSyncOutcome.SKIPPED, sync.sync(FxSyncTrigger.START))
        assertTrue(server.urls.isEmpty())
        val unchanged = db.overview().settings()!!.revision

        clock.now = Instant.parse("2026-10-29T01:00:00Z")
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        assertEquals(listOf("date=2026-10-29"), queries())
        assertEquals(unchanged + 1, db.overview().settings()!!.revision)

        server.urls.clear()
        clock.now = Instant.parse("2026-10-31T23:00:00Z")   // November 1st in Shanghai
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        assertEquals(listOf("date=2026-10-30", "from=2026-10-31&to=2026-11-01"), queries())
        val usd = history("USD")
        assertEquals((day("2026-10-25")..day("2026-11-01")).map { LocalDate.ofEpochDay(it).toString() }, usd.map { it.first })
        assertEquals(Triple("2026-11-01", "2026-10-31", "0.15031"), usd.last())
        assertEquals(inverse("0.15031"), rates()[("USD" to "CNY")])
    }

    @Test fun failure_is_not_a_success_and_start_retries_after_thirty_minutes() = runBlocking {
        val before = rates()
        server.failure = IOException("offline")
        assertEquals(FxSyncOutcome.FAILED, sync.sync(FxSyncTrigger.START))
        var state = db.fx().localState()!!
        assertNull(state.last_success_day)
        assertEquals("NETWORK", state.last_error)
        assertEquals(clock.millis(), state.last_attempt_at_ms)
        assertEquals(before, rates())
        assertTrue(db.fx().history("CNY").isEmpty())
        assertEquals(FxSyncError.NETWORK, sync.observeNotice().first()!!.error)
        assertEquals(FxSyncError.NETWORK, sync.observeStatus().first().lastError)

        server.urls.clear()
        server.failure = null
        clock.now = clock.now.plusSeconds(29 * 60)
        assertEquals(FxSyncOutcome.SKIPPED, sync.sync(FxSyncTrigger.START))
        assertTrue(server.urls.isEmpty())
        clock.now = clock.now.plusSeconds(60)
        server.status = 503
        assertEquals(FxSyncOutcome.FAILED, sync.sync(FxSyncTrigger.START))
        assertEquals("SERVICE", db.fx().localState()!!.last_error)
        clock.now = clock.now.plusSeconds(5 * 60)
        server.status = 429
        // Manual updates ignore the 30-minute wait and never raise a notice.
        sync.consumeNotice(sync.observeNotice().first()!!.id)
        assertEquals(FxSyncOutcome.FAILED, sync.sync(FxSyncTrigger.MANUAL))
        assertEquals("RATE_LIMITED", db.fx().localState()!!.last_error)
        assertNull(sync.observeNotice().first())
        assertEquals(FxSyncOutcome.SKIPPED, sync.sync(FxSyncTrigger.MANUAL))   // within 10 seconds

        server.status = 200
        clock.now = clock.now.minusSeconds(3600)   // clock set back: the wait counts as passed
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        state = db.fx().localState()!!
        assertNull(state.last_error)
        assertEquals(day("2026-10-28"), state.last_success_day)
    }

    @Test fun time_zone_changes_and_a_clock_set_back_follow_the_local_day() = runBlocking {
        clock.now = Instant.parse("2026-10-28T15:30:00Z")   // 23:30 in Shanghai
        sync.sync(FxSyncTrigger.START)
        val history = db.fx().history("CNY")
        val rates = rates()

        server.urls.clear()
        clock.zoneId = ZoneId.of("Asia/Tokyo")              // 00:30 on the 29th
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        assertEquals(listOf("date=2026-10-29"), queries())
        assertEquals(day("2026-10-29"), db.fx().localState()!!.last_success_day)

        server.urls.clear()
        val afterTokyo = db.fx().history("CNY")
        val ratesAfterTokyo = rates()
        clock.zoneId = ZoneId.of("America/Los_Angeles")     // back on the 28th: nothing new to fetch
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        assertTrue(server.urls.isEmpty())
        assertEquals(afterTokyo, db.fx().history("CNY"))
        assertEquals(ratesAfterTokyo, rates())
        assertEquals(day("2026-10-28"), db.fx().localState()!!.last_success_day)
        assertTrue(history.size < afterTokyo.size && rates != ratesAfterTokyo)
    }

    @Test fun changes_during_the_request_discard_the_result() = runBlocking {
        val original = rates()
        // Switched off while fetching: today's rates stay as they were.
        server.onRequest = { db.fx().setAutoEnabled(false) }
        assertEquals(FxSyncOutcome.DISCARDED, sync.sync(FxSyncTrigger.START))
        assertEquals(original, rates())
        assertTrue(db.fx().history("CNY").isEmpty())
        assertNull(db.fx().localState()!!.last_success_day)

        // A restore committed meanwhile.
        db.fx().setAutoEnabled(true)
        server.onRequest = { db.openHelper.writableDatabase.execSQL("UPDATE local_maintenance_state SET last_restore_attempt_id='other'") }
        assertEquals(FxSyncOutcome.DISCARDED, sync.sync(FxSyncTrigger.START))
        assertTrue(db.fx().history("CNY").isEmpty())
        assertEquals(original, rates())

        // The base changed meanwhile: nothing is stored under either base.
        server.onRequest = {
            server.onRequest = {}
            RoomSettings(db, clock).applyChange(SaveFinancialSettings(db.overview().settings()!!.revision, Currency.of("USD"), emptyList()))
        }
        assertEquals(FxSyncOutcome.DISCARDED, sync.sync(FxSyncTrigger.START))
        assertTrue(db.fx().history("CNY").isEmpty() && db.fx().history("USD").isEmpty())
        assertTrue(rates().isEmpty())

        // A failure while switched off is not recorded either.
        server.onRequest = { db.fx().setAutoEnabled(false) }
        server.failure = IOException("offline")
        assertEquals(FxSyncOutcome.DISCARDED, sync.sync(FxSyncTrigger.ENABLED))
        assertNull(db.fx().localState()!!.last_error)
    }

    @Test fun switched_off_requests_nothing_and_keeps_the_last_automatic_rates() = runBlocking {
        sync.sync(FxSyncTrigger.START)
        val automatic = rates()
        sync.setAutoEnabled(false, FxWriteGate { it() })
        server.urls.clear()
        clock.now = clock.now.plusSeconds(86_400)
        FxSyncTrigger.entries.forEach { assertEquals(FxSyncOutcome.SKIPPED, sync.sync(it)) }
        assertTrue(server.urls.isEmpty())
        assertEquals(automatic, rates())
        assertEquals(emptyList<String>(), sync.observeStatus().first().missingCurrencies)
    }

    @Test fun missing_in_use_currency_is_reported_once_per_background_sync() = runBlocking {
        cash("KWD")
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        assertEquals(listOf("KWD"), sync.observeStatus().first().missingCurrencies)
        val notice = sync.observeNotice().first()!!
        assertEquals(listOf("KWD"), notice.missingCurrencies)
        assertNull(notice.error)
        assertEquals(date("2026-10-28"), notice.dataDate)
        sync.consumeNotice(notice.id)
        assertNull(sync.observeNotice().first())
        assertNull(rates()[("KWD" to "CNY")])
    }

    @Test fun new_currency_in_use_gets_a_rate_from_history_without_a_request() = runBlocking {
        sync.sync(FxSyncTrigger.START)
        sync.attach(FxWriteGate { it() })
        server.urls.clear()
        cash("GBP")
        withTimeout(10_000) {
            while (rates()[("GBP" to "CNY")] == null) kotlinx.coroutines.delay(50)
        }
        assertEquals(inverse("0.11028"), rates()[("GBP" to "CNY")])
        assertTrue(server.urls.isEmpty())
    }


    @Test fun switching_back_on_the_same_day_replaces_manual_rates_without_a_request() = runBlocking {
        sync.sync(FxSyncTrigger.START)
        db.fx().setAutoEnabled(false)
        RoomSettings(db, clock).applyChange(SaveFinancialSettings(db.overview().settings()!!.revision, Currency.of("CNY"), listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("9")), FxRate(Currency.of("CNY"), Currency.of("USD"), BigDecimal("0.14")))))
        server.urls.clear()
        db.fx().setAutoEnabled(true)
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.ENABLED))
        assertTrue(server.urls.isEmpty())
        assertEquals(inverse("0.15028"), rates()[("USD" to "CNY")])
    }

    @Test fun a_base_change_during_a_running_sync_runs_once_it_finishes() = runBlocking {
        // While the first request is in flight the base changes and its trigger arrives: it must not be lost.
        server.onRequest = {
            server.onRequest = {}
            RoomSettings(db, clock).applyChange(SaveFinancialSettings(db.overview().settings()!!.revision, Currency.of("USD"), emptyList()))
            assertEquals(FxSyncOutcome.SKIPPED, sync.sync(FxSyncTrigger.BASE_CHANGED))
        }
        assertEquals(FxSyncOutcome.DISCARDED, sync.sync(FxSyncTrigger.START))
        withTimeout(10_000) {
            while (db.fx().history("USD").isEmpty()) kotlinx.coroutines.delay(50)
        }
        assertTrue(server.urls.any { it.contains("base=USD") })
        assertTrue(db.fx().history("CNY").isEmpty())
    }

    @Test fun a_long_gap_is_stored_a_year_at_a_time_and_resumes_after_a_failure() = runBlocking {
        val baseline = date("2024-01-01")
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=? WHERE id=1",
            arrayOf(baseline.atStartOfDay(shanghai).toInstant().toEpochMilli()))
        var day = date("2023-12-20")
        while (day <= date("2026-10-31")) {
            server.published.getValue("USD")[day] = "0.1" + (day.toEpochDay() % 1000).toString().padStart(3, '0')
            day = day.plusDays(1)
        }
        // The third request (the second year's range) fails.
        server.onRequest = { if (server.urls.size == 3) throw IOException("offline") }
        assertEquals(FxSyncOutcome.FAILED, sync.sync(FxSyncTrigger.START))
        assertEquals(listOf("date=2024-01-01", "from=2024-01-02&to=2024-12-31", "from=2025-01-01&to=2025-12-31"), queries())
        var usd = db.fx().history("CNY").filter { it.quote_currency == "USD" }.map { it.local_day }
        assertEquals((day("2024-01-01")..day("2024-12-31")).toList(), usd)   // the first year stays stored
        assertEquals("NETWORK", db.fx().localState()!!.last_error)
        assertNull(db.fx().localState()!!.last_success_day)

        server.onRequest = {}
        server.urls.clear()
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.ENABLED))
        assertEquals(listOf("date=2025-01-01", "from=2025-01-02&to=2025-12-31", "from=2026-01-01&to=2026-10-28"), queries())
        usd = db.fx().history("CNY").filter { it.quote_currency == "USD" }.map { it.local_day }
        assertEquals((day("2024-01-01")..day("2026-10-28")).toList(), usd)   // no gap where it resumed
        assertEquals(day("2026-10-28"), db.fx().localState()!!.last_success_day)
    }

    @Test fun a_value_sent_from_before_a_year_s_range_is_carried_into_it() = runBlocking {
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=? WHERE id=1",
            arrayOf(date("2024-06-01").atStartOfDay(shanghai).toInstant().toEpochMilli()))
        var day = date("2024-05-20")
        while (day <= date("2026-10-31")) {
            // Nothing is published on New Year's Day, so each later year's range starts on an empty day.
            if (day.dayOfYear != 1) server.published.getValue("USD")[day] = "0.1" + (day.toEpochDay() % 1000).toString().padStart(3, '0')
            day = day.plusDays(1)
        }
        server.carryIntoRanges = true
        assertEquals(FxSyncOutcome.SUCCEEDED, sync.sync(FxSyncTrigger.START))
        val usd = history("USD").associateBy { it.first }
        assertEquals((day("2024-06-01")..day("2026-10-28")).count(), usd.size)
        assertEquals("2024-12-31", usd.getValue("2025-01-01").second)
        assertEquals("2025-12-31", usd.getValue("2026-01-01").second)
        assertEquals("2026-01-02", usd.getValue("2026-01-02").second)
        assertNull(db.fx().localState()!!.last_error)
    }

    @Test fun new_rates_invalidate_only_days_valued_at_today_s_rate() = runBlocking {
        // Every currency in use has a stored rate from the baseline on.
        (20..26).forEach { server.published.getValue("JPY")[date("2026-10-%02d".format(it))] = "23.%02d".format(it) }
        sync.sync(FxSyncTrigger.START)
        assertEquals(day("2026-10-25"), db.statistics().state()!!.earliest_invalidated_epoch_day)
        db.statistics().markValid(db.statistics().state()!!.source_revision)

        clock.now = Instant.parse("2026-10-29T01:00:00Z")
        sync.sync(FxSyncTrigger.START)
        // Rates changed, but every earlier day keeps its own stored rate: only the new day is recomputed.
        assertEquals(day("2026-10-29"), db.statistics().state()!!.earliest_invalidated_epoch_day)
        db.statistics().markValid(db.statistics().state()!!.source_revision)

        // A currency in use without any stored day is valued at today's rate everywhere.
        cash("KWD")
        clock.now = Instant.parse("2026-10-30T01:00:00Z")
        sync.sync(FxSyncTrigger.START)
        assertEquals(day("2026-10-25"), db.statistics().state()!!.earliest_invalidated_epoch_day)
    }
}
