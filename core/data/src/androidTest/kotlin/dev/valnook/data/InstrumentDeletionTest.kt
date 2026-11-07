package dev.valnook.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomCash
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Deleting an instrument takes its positions, trades, prices and history everywhere, but leaves cash as it is. */
@RunWith(AndroidJUnit4::class)
class InstrumentDeletionTest {
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private val clock = Clock.fixed(Instant.parse("2026-11-08T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private fun id() = UUID.randomUUID().toString()
    private fun e(text: String) = DecimalRules.parse_e8(text)
    private val sql get() = db.openHelper.writableDatabase

    @Before fun prepare() {
        db = ValnookDatabase.inMemory(ApplicationProvider.getApplicationContext())
        commands = RoomFinancialCommands(db, clock)
    }
    @After fun close() { db.close() }

    private fun count(query: String, vararg args: Any): Long = sql.query(query, args).use { it.moveToFirst(); it.getLong(0) }
    private fun expect(code: ErrorCode, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("expected $code") } catch (error: DomainException) { assertEquals(code, error.code) }
    }
    private suspend fun cashAccount(account: Long, minor: Long) {
        commands.execute(SetCashBalance(id(), account, "CNY", minor, db.ledger().cash_one(account, "CNY")?.revision))
    }
    private fun instrumentOf(position: Long) = runBlocking { db.ledger().investment(position)!!.instrument_id }

    @Test fun an_instrument_without_trades_goes_with_one_confirmation() = runBlocking {
        val account = commands.testAccount("合成账户")
        val type = commands.testType("合成类型")
        val position = commands.testInvestment(account, "合成空标的", "SYNE", type, "CNY", 0, e("1"), null)
        val instrument = instrumentOf(position)
        val preview = commands.previewInstrumentDeletion(instrument)
        assertFalse(preview.needsCode)
        assertEquals("", preview.code)
        assertEquals(1, preview.accountCount)
        commands.execute(DeleteInstrument(id(), instrument, preview.revision, DeletionConfirmation(preview.ticket, "")))
        assertNull(db.instruments().instrument(instrument))
        assertEquals(0L, count("SELECT COUNT(*) FROM investments WHERE instrument_id=?", instrument))
        // Without trades, no preview is needed at all.
        val other = instrumentOf(commands.testInvestment(account, "合成空标的二", "SYNF", type, "CNY", 0, e("1"), null))
        commands.execute(DeleteInstrument(id(), other, db.instruments().instrument(other)!!.revision))
        assertNull(db.instruments().instrument(other))
    }

    @Test fun an_instrument_with_trades_needs_the_code_and_leaves_cash_untouched() = runBlocking {
        RoomSettings(db, clock).applyChange(SaveFinancialSettings(0, Currency.of("CNY"), emptyList()))
        val account = commands.testAccount("合成账户")
        cashAccount(account, 100_000)
        val type = commands.testType("合成类型")
        val position = commands.testInvestment(account, "合成交易标的", "SYNT", type, "CNY", 0, e("10"), null)
        val instrument = instrumentOf(position)
        val now = clock.millis()
        commands.execute(RecordInvestmentTrade(id(), position, Direction.BUY, e("3"), e("10"), now - 2000, true))
        commands.execute(RecordInvestmentTrade(id(), position, Direction.SELL, e("1"), e("12"), now - 1000, true))
        commands.testUpdatePrice(db, position, e("11"))
        // Another instrument in the same account keeps everything.
        val kept = commands.testInvestment(account, "合成保留标的", "SYNK", type, "CNY", e("1"), e("5"), e("5"))
        val keptTrades = count("SELECT COUNT(*) FROM investment_trades WHERE investment_id=?", kept)
        val balance = db.ledger().cash_one(account, "CNY")!!.balance_minor
        val entries = count("SELECT COUNT(*) FROM cash_entries")
        val tradeIds = sql.query("SELECT id FROM investment_trades WHERE investment_id=?", arrayOf(position)).use {
            buildList { while (it.moveToNext()) add(it.getLong(0)) } }
        assertTrue(count("SELECT COUNT(*) FROM audit_events WHERE entity_kind='INVESTMENT_TRADE' AND entity_id IN (${tradeIds.joinToString()})") > 0)

        val preview = commands.previewInstrumentDeletion(instrument)
        assertTrue(preview.needsCode)
        assertEquals(6, preview.code.length)
        assertEquals(1, preview.accountCount)
        assertEquals(2, preview.tradeCount)
        assertEquals(2, preview.cashEntryCount)
        assertEquals(e("2"), preview.holdingQuantityE8)
        assertEquals(0, BigDecimal("22").compareTo(preview.marketValue))
        assertTrue(preview.priceCount >= 1)
        expect(ErrorCode.FORMAT) { commands.execute(DeleteInstrument(id(), instrument, preview.revision)) }
        expect(ErrorCode.FORMAT) { commands.execute(DeleteInstrument(id(), instrument, preview.revision,
            DeletionConfirmation(preview.ticket, if (preview.code == "000000") "111111" else "000000"))) }
        // Nothing is waiting to be recalculated, so what the deletion asks for shows on its own.
        sql.execSQL("UPDATE statistics_state SET earliest_invalidated_epoch_day=NULL")
        val operation = id()
        commands.execute(DeleteInstrument(operation, instrument, preview.revision, DeletionConfirmation(preview.ticket, preview.code)))

        assertNull(db.instruments().instrument(instrument))
        assertEquals(0L, count("SELECT COUNT(*) FROM investments WHERE instrument_id=?", instrument))
        assertEquals(0L, count("SELECT COUNT(*) FROM investment_trades WHERE investment_id=?", position))
        assertEquals(0L, count("SELECT COUNT(*) FROM instrument_price_history WHERE instrument_id=?", instrument))
        assertEquals(0L, count("SELECT COUNT(*) FROM statistics_baseline_items WHERE instrument_id=?", instrument))
        // Cash is as it was, now told apart as a deleted instrument's trades.
        assertEquals(balance, db.ledger().cash_one(account, "CNY")!!.balance_minor)
        assertEquals(entries, count("SELECT COUNT(*) FROM cash_entries"))
        assertEquals(2L, count("SELECT COUNT(*) FROM cash_entries WHERE source_kind='TRADE_REMOVED' AND source_id IS NULL"))
        val shown = RoomCash(db.ledger()).observe_entries(account, "CNY", 100).first().filter { it.source == CashSource.TRADE_REMOVED }
        assertEquals(listOf(-3000L, 1200L).sorted(), shown.map { it.delta_minor }.sorted())
        assertEquals(balance, RoomCash(db.ledger()).observe_entries(account, "CNY", 100).first().sumOf { it.delta_minor })
        // Its history went with it; the deletion is recorded by IDs only.
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events WHERE entity_kind='INVESTMENT_TRADE' AND entity_id IN (${tradeIds.joinToString()})"))
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events WHERE entity_kind='INVESTMENT' AND entity_id=?", position.toString()))
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_events WHERE entity_kind='INSTRUMENT' AND entity_id=?", instrument.toString()))
        assertEquals(0L, count("SELECT COUNT(*) FROM audit_events WHERE operation_id=? AND (after_json LIKE '%合成交易标的%' OR after_json LIKE '%' || ? || '%')",
            operation, preview.code))
        // The other instrument and its records stay.
        assertEquals(keptTrades, count("SELECT COUNT(*) FROM investment_trades WHERE investment_id=?", kept))
        assertNotNull(db.ledger().investment(kept))
        // Statistics start over from the baseline and still load.
        val baselineDay = Instant.ofEpochMilli(db.statistics().state()!!.baseline_at_ms).atZone(clock.zone).toLocalDate().toEpochDay()
        assertEquals(baselineDay, db.statistics().state()!!.earliest_invalidated_epoch_day)
        val today = LocalDate.now(clock)
        val points = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, today.year, today.monthValue))).points.filter { !it.future && it.value != null }
        assertTrue(points.isNotEmpty())
        // The same request again answers from its receipt.
        assertEquals(instrument, commands.execute(DeleteInstrument(operation, instrument, preview.revision,
            DeletionConfirmation(preview.ticket, preview.code))).id)
    }

    @Test fun a_preview_no_longer_holds_once_the_instrument_changed() = runBlocking {
        val account = commands.testAccount("合成账户")
        val type = commands.testType("合成类型")
        val position = commands.testInvestment(account, "合成变动标的", "SYNV", type, "CNY", e("1"), e("10"), e("10"))
        val instrument = instrumentOf(position)
        val preview = commands.previewInstrumentDeletion(instrument)
        commands.execute(RecordInvestmentTrade(id(), position, Direction.BUY, e("1"), e("10"), clock.millis(), false))
        expect(ErrorCode.STALE_RECORD) { commands.execute(DeleteInstrument(id(), instrument, preview.revision,
            DeletionConfirmation(preview.ticket, preview.code))) }
        val fresh = commands.previewInstrumentDeletion(instrument)
        expect(ErrorCode.STALE_RECORD) { commands.execute(DeleteInstrument(id(), instrument, fresh.revision + 1,
            DeletionConfirmation(fresh.ticket, fresh.code))) }
        expect(ErrorCode.NOT_FOUND) { commands.execute(DeleteInstrument(id(), 9999, 1)) }
        commands.cancelInstrumentDeletion(fresh.ticket)
        expect(ErrorCode.STALE_RECORD) { commands.execute(DeleteInstrument(id(), instrument, fresh.revision,
            DeletionConfirmation(fresh.ticket, fresh.code))) }
        assertNotNull(db.instruments().instrument(instrument))
    }
}
