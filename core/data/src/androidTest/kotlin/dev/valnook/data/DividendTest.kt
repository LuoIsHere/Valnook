package dev.valnook.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomCash
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
import java.time.ZoneId
import java.util.UUID

/** A dividend adds to realized profit and, when linked, to cash; it moves no units and no cost. */
@RunWith(AndroidJUnit4::class)
class DividendTest {
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
    private suspend fun realized(position: Long) = BigDecimal(db.ledger().investment(position)!!.realized_profit)
    private suspend fun balance(account: Long) = db.ledger().cash_one(account, "CNY")!!.balance_minor

    private suspend fun setup(): Triple<Long, Long, Long> {
        val account = commands.testAccount("合成账户")
        commands.execute(SetCashBalance(id(), account, "CNY", 100_000, null))
        val type = commands.testType("合成类型")
        val position = commands.testInvestment(account, "合成派息基金", "SYND", type, "CNY", 0, e("10"), null)
        commands.execute(RecordInvestmentTrade(id(), position, Direction.BUY, e("10"), e("10"), clock.millis() - 10_000, false))
        return Triple(account, position, db.ledger().investment(position)!!.instrument_id)
    }

    @Test fun recording_editing_and_deleting_a_dividend_follow_realized_profit_and_cash() = runBlocking {
        val (account, position, _) = setup()
        val cost = db.ledger().investment(position)!!.remaining_cost
        val start = balance(account)
        val trade = commands.execute(RecordDividend(id(), position, 1234, clock.millis(), true)).id
        assertEquals(0, BigDecimal("12.34").compareTo(realized(position)))
        assertEquals(cost, db.ledger().investment(position)!!.remaining_cost)
        assertEquals(e("10"), db.ledger().investment(position)!!.holding_quantity_e8)
        assertEquals(start + 1234, balance(account))
        val entry = RoomCash(db.ledger()).observe_entries(account, "CNY", 100).first().single { it.source == CashSource.DIVIDEND }
        assertEquals(1234L, entry.delta_minor)
        assertEquals(trade, entry.source_id)
        assertEquals(position, entry.investment_id)
        // A larger amount, then unlinked: cash follows each time.
        var revision = db.trades().trade(trade)!!.revision
        commands.execute(EditDividend(id(), trade, revision, 2000, clock.millis(), true))
        assertEquals(0, BigDecimal("20").compareTo(realized(position)))
        assertEquals(start + 2000, balance(account))
        revision = db.trades().trade(trade)!!.revision
        commands.execute(EditDividend(id(), trade, revision, 2000, clock.millis(), false))
        assertEquals(start, balance(account))
        revision = db.trades().trade(trade)!!.revision
        commands.execute(EditDividend(id(), trade, revision, 500, clock.millis(), true))
        assertEquals(start + 500, balance(account))
        revision = db.trades().trade(trade)!!.revision
        commands.execute(DeleteInvestmentTrade(id(), trade, revision))
        assertEquals(0, BigDecimal.ZERO.compareTo(realized(position)))
        assertEquals(start, balance(account))
    }

    @Test fun a_sold_out_position_still_receives_a_dividend() = runBlocking {
        val (account, position, _) = setup()
        commands.execute(RecordInvestmentTrade(id(), position, Direction.SELL, e("10"), e("12"), clock.millis() - 5_000, false))
        commands.execute(RecordDividend(id(), position, 300, clock.millis(), false))
        assertEquals(0, BigDecimal("23").compareTo(realized(position)))
        assertEquals(0L, db.ledger().investment(position)!!.holding_quantity_e8)
        assertEquals(100_000L, balance(account))
    }

    @Test fun dividends_are_refused_where_they_cannot_apply() {
        val (account, position, instrument) = runBlocking { setup() }
        // Before anything was bought.
        expect(ErrorCode.HISTORY_CONFLICT) { commands.execute(RecordDividend(id(), position, 100, clock.millis() - 20_000, false)) }
        expect(ErrorCode.POSITIVE) { commands.execute(RecordDividend(id(), position, 0, clock.millis(), false)) }
        expect(ErrorCode.NOT_FOUND) { commands.execute(RecordDividend(id(), 9999, 100, clock.millis(), false)) }
        // A position never bought cannot receive one either.
        val other = runBlocking { commands.testInvestment(account, "合成未买标的", "SYNX", commands.testType("合成类型二"),
            "CNY", 0, e("1"), null) }
        expect(ErrorCode.HISTORY_CONFLICT) { commands.execute(RecordDividend(id(), other, 100, clock.millis(), false)) }
        // Trades and dividends keep their own commands.
        expect(ErrorCode.FORMAT) { commands.execute(RecordInvestmentTrade(id(), position, Direction.DIVIDEND, 0, 0, clock.millis(), false)) }
        val dividend = runBlocking { commands.execute(RecordDividend(id(), position, 100, clock.millis(), false)).id }
        val dividendRevision = runBlocking { db.trades().trade(dividend)!!.revision }
        expect(ErrorCode.FORMAT) { commands.execute(EditInvestmentTrade(id(), dividend, dividendRevision, Direction.SELL,
            e("1"), e("10"), clock.millis(), false)) }
        val buy = sql.query("SELECT id,revision FROM investment_trades WHERE direction='BUY' AND investment_id=?", arrayOf(position)).use {
            it.moveToFirst(); it.getLong(0) to it.getLong(1) }
        expect(ErrorCode.FORMAT) { commands.execute(EditDividend(id(), buy.first, buy.second, 100, clock.millis(), false)) }
        // Moving the purchase after the dividend would leave it with nothing bought before.
        expect(ErrorCode.HISTORY_CONFLICT) { commands.execute(EditInvestmentTrade(id(), buy.first, buy.second, Direction.BUY,
            e("10"), e("10"), clock.millis() + 60_000, false)) }
        assertNotNull(runBlocking { db.instruments().instrument(instrument) })
    }

    @Test fun deleting_the_instrument_keeps_the_dividends_cash_as_a_deleted_instruments_dividend() = runBlocking {
        val (account, position, instrument) = setup()
        commands.execute(RecordDividend(id(), position, 800, clock.millis(), true))
        val start = balance(account)
        val preview = commands.previewInstrumentDeletion(instrument)
        assertEquals(1, preview.cashEntryCount)
        commands.execute(DeleteInstrument(id(), instrument, preview.revision, DeletionConfirmation(preview.ticket, preview.code)))
        assertEquals(start, balance(account))
        assertEquals(1L, count("SELECT COUNT(*) FROM cash_entries WHERE source_kind='DIVIDEND_REMOVED' AND source_id IS NULL"))
        assertEquals(0L, count("SELECT COUNT(*) FROM cash_entries WHERE source_kind='DIVIDEND'"))
        assertEquals(CashSource.DIVIDEND_REMOVED,
            RoomCash(db.ledger()).observe_entries(account, "CNY", 100).first().single { it.delta_minor == 800L }.source)
    }
}
