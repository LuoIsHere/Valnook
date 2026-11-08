package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomInstruments
import dev.valnook.data.repository.RoomInvestments
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.Trade
import dev.valnook.domain.model.TradeCursor
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** One instrument's page reads every account's trades and the instrument's recent prices. */
@RunWith(AndroidJUnit4::class)
class InstrumentAggregateDataTest {
    private val hongKong = ZoneId.of("Asia/Hong_Kong")
    private var now = Instant.parse("2026-11-01T04:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = hongKong
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private lateinit var investments: RoomInvestments
    private lateinit var instruments: RoomInstruments
    private var type = 0L

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        commands = RoomFinancialCommands(db, clock)
        investments = RoomInvestments(db)
        instruments = RoomInstruments(db) { hongKong }
        type = commands.testType()
    }

    @After fun close() { db.close() }

    private fun at(date: String, time: String = "12:00") = LocalDateTime.parse("${date}T$time").atZone(hongKong).toInstant().toEpochMilli()

    private suspend fun position(account: Long, instrument: Long) =
        commands.execute(CreateInvestmentPosition(testOperationId(), account, instrument)).id

    private suspend fun buy(position: Long, time: Long, quantity: Long = 100_000_000) =
        commands.execute(RecordInvestmentTrade(testOperationId(), position, Direction.BUY, quantity, 150_000_000, time, false)).id

    private suspend fun pagedOneByOne(instrument: Long, month: YearMonth): List<Trade> {
        val rows = mutableListOf<Trade>()
        var cursor: TradeCursor? = null
        while (true) {
            val next = investments.instrumentTradeMonthPage(instrument, LedgerMonth(month, hongKong), cursor, 1)
            if (next.isEmpty()) break
            rows += next
            cursor = TradeCursor(next.last().occurred_at_ms, next.last().id)
        }
        return rows
    }

    @Test fun trades_of_every_account_holding_it_come_newest_first_by_month() = runBlocking {
        val fund = commands.testInstrument("Fund", "F1", type, "CNY", 150_000_000)
        val other = commands.testInstrument("Other", "O1", type, "CNY", 150_000_000)
        val a = position(commands.testAccount("A"), fund)
        val b = position(commands.testAccount("B"), fund)
        val elsewhere = position(commands.testAccount("C"), other)
        val first = buy(a, at("2026-09-03", "10:00"))
        val second = buy(b, at("2026-09-03", "10:00"))
        val third = buy(a, at("2026-09-20"))
        buy(b, at("2026-10-02"))
        buy(elsewhere, at("2026-09-10"))

        val september = investments.instrumentTradeMonthPage(fund, LedgerMonth(YearMonth.of(2026, 9), hongKong), null, 50)
        // Same moment: the later record first, as on a single position's page.
        assertEquals(listOf(third, second, first), september.map { it.id })
        assertEquals(setOf(a, b), september.map { it.investment_id }.toSet())
        assertEquals(september, pagedOneByOne(fund, YearMonth.of(2026, 9)))
        assertEquals(1, investments.instrumentTradeMonthPage(fund, LedgerMonth(YearMonth.of(2026, 10), hongKong), null, 50).size)
        assertTrue(investments.instrumentTradeMonthPage(fund, LedgerMonth(YearMonth.of(2026, 8), hongKong), null, 50).isEmpty())
    }

    @Test fun the_revision_moves_with_a_trade_in_any_account_and_a_new_position() = runBlocking {
        val fund = commands.testInstrument("Fund", "F1", type, "CNY", 150_000_000)
        val a = position(commands.testAccount("A"), fund)
        val start = investments.observeInstrumentTradeRevision(fund).first()
        val b = position(commands.testAccount("B"), fund)
        val opened = investments.observeInstrumentTradeRevision(fund).first()
        assertNotEquals(start, opened)
        buy(b, at("2026-09-03"))
        val traded = investments.observeInstrumentTradeRevision(fund).first()
        assertNotEquals(opened, traded)
        buy(a, at("2026-09-04"))
        assertNotEquals(traded, investments.observeInstrumentTradeRevision(fund).first())
        assertEquals("", investments.observeInstrumentTradeRevision(fund + 99).first())
    }

    private suspend fun savePrice(instrument: Long, priceE5: Long, recordUnchanged: Boolean) {
        val revision = db.instruments().instrument(instrument)!!.revision
        commands.execute(UpdateInstrumentPrices(testOperationId(), listOf(InstrumentPriceChange(instrument, revision, priceE5)), recordUnchanged))
    }

    @Test fun the_trend_keeps_the_latest_thirty_prices_and_a_price_saved_again_by_hand() = runBlocking {
        val fund = commands.testInstrument("Fund", "F1", type, "CNY", 100_000_000)
        repeat(34) { day ->
            now = now.plus(Duration.ofDays(1))
            savePrice(fund, 100_000L + day + 1, recordUnchanged = true)
        }
        val trend = instruments.observePriceHistory(fund).first()
        assertEquals(InstrumentRepository.PRICE_TREND_POINTS, trend.size)
        // Newest first; the oldest ones fall off the trend but stay stored for the statistics.
        assertEquals(100_034L, trend.first().priceE5)
        assertEquals(100_005L, trend.last().priceE5)
        assertEquals(35, db.statistics().prices().count { it.instrument_id == fund })

        // The same price saved by hand is today's price: dated now and drawn.
        now = now.plus(Duration.ofDays(1))
        savePrice(fund, 100_034L, recordUnchanged = true)
        val again = instruments.observePriceHistory(fund).first()
        assertEquals(now.toEpochMilli(), again.first().atMs)
        assertEquals(100_034L, again.first().priceE5)
        assertEquals(now.toEpochMilli(), db.instruments().instrument(fund)!!.price_updated_at_ms)

        // The web batch leaves unchanged prices alone.
        val dated = now.toEpochMilli()
        now = now.plus(Duration.ofDays(1))
        savePrice(fund, 100_034L, recordUnchanged = false)
        assertEquals(dated, instruments.observePriceHistory(fund).first().first().atMs)
        assertEquals(dated, db.instruments().instrument(fund)!!.price_updated_at_ms)
    }

    @Test fun a_trade_can_set_its_price_as_the_current_price() = runBlocking {
        // Created at 12:00 on 1 November in Hong Kong at 1.
        val fund = commands.testInstrument("Fund", "F1", type, "CNY", 100_000_000)
        val held = position(commands.testAccount("A"), fund)
        fun at(time: String) = LocalDateTime.parse("2026-11-02T$time").atZone(hongKong).toInstant().toEpochMilli()
        suspend fun trade(direction: Direction, priceE8: Long, time: String, set: Boolean) =
            commands.execute(RecordInvestmentTrade(testOperationId(), held, direction, 100_000_000, priceE8, at(time),
                false, setAsCurrentPrice = set)).id
        suspend fun stored() = db.statistics().prices().filter { it.instrument_id == fund }
        fun tradeCount() = db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM investment_trades WHERE investment_id=? AND is_deleted=0", arrayOf<Any>(held))
            .use { it.moveToFirst(); it.getInt(0) }

        // Left off, the current price stays.
        trade(Direction.BUY, 110_000_000, "09:00", set = false)
        assertEquals(100_000L, db.instruments().instrument(fund)!!.current_price_e5)
        assertEquals(1, stored().size)

        // On: the price, at five decimals, is the current one, dated at the trade.
        val bought = trade(Direction.BUY, 123_456_789, "10:00", set = true)
        db.instruments().instrument(fund)!!.let {
            assertEquals(123_457L, it.current_price_e5)
            assertEquals(at("10:00"), it.price_updated_at_ms)
        }
        assertEquals(123_457L, instruments.observePriceHistory(fund).first().first().priceE5)
        assertEquals(2, stored().size)

        // A sell at the same price still counts as an update.
        trade(Direction.SELL, 123_457_000, "11:00", set = true)
        assertEquals(3, stored().size)
        assertEquals(at("11:00"), db.instruments().instrument(fund)!!.price_updated_at_ms)

        // An older trade would put an old price over a newer one: refused, and nothing is written.
        val trades = tradeCount()
        val refused = runCatching { trade(Direction.BUY, 99_000_000, "10:30", set = true) }.exceptionOrNull()
        assertEquals(ErrorCode.STALE_RECORD, (refused as DomainException).code)
        assertEquals(trades, tradeCount())
        assertEquals(123_457L, db.instruments().instrument(fund)!!.current_price_e5)
        assertEquals(3, stored().size)

        // Deleting the trade leaves the price it recorded.
        val revision = db.trades().trade(bought)!!.revision
        commands.execute(DeleteInvestmentTrade(testOperationId(), bought, revision))
        assertEquals(3, stored().size)
        assertEquals(123_457L, db.instruments().instrument(fund)!!.current_price_e5)
    }

    @Test fun the_trend_keeps_only_the_last_update_of_each_day() = runBlocking {
        // Created at 12:00 on 1 November in Hong Kong.
        val fund = commands.testInstrument("Fund", "F1", type, "CNY", 100_000_000)
        val created = now.toEpochMilli()
        // Forty updates on 2 November, the last at 23:59; more updates than the trend has days.
        now = LocalDateTime.parse("2026-11-02T08:00").atZone(hongKong).toInstant()
        repeat(39) { step ->
            savePrice(fund, 100_100L + step, recordUnchanged = true)
            now = now.plus(Duration.ofMinutes(10))
        }
        now = LocalDateTime.parse("2026-11-02T23:59").atZone(hongKong).toInstant()
        savePrice(fund, 100_200L, recordUnchanged = true)
        val lastOnTheSecond = now.toEpochMilli()
        // Just past midnight is the next day.
        now = LocalDateTime.parse("2026-11-03T00:01").atZone(hongKong).toInstant()
        savePrice(fund, 100_300L, recordUnchanged = true)

        val trend = instruments.observePriceHistory(fund).first()
        assertEquals(listOf(100_300L, 100_200L, 100_000L), trend.map { it.priceE5 })
        assertEquals(listOf(now.toEpochMilli(), lastOnTheSecond, created), trend.map { it.atMs })
        // Every update stays stored for the statistics.
        assertEquals(42, db.statistics().prices().count { it.instrument_id == fund })

        // A later update the same day replaces that day's point.
        now = now.plus(Duration.ofHours(9))
        savePrice(fund, 100_400L, recordUnchanged = true)
        val again = instruments.observePriceHistory(fund).first()
        assertEquals(listOf(100_400L, 100_200L, 100_000L), again.map { it.priceE5 })
        assertEquals(now.toEpochMilli(), again.first().atMs)
    }

    @Test fun the_flag_is_part_of_the_request() = runBlocking {
        val fund = commands.testInstrument("Fund", "F1", type, "CNY", 100_000_000)
        val change = listOf(InstrumentPriceChange(fund, db.instruments().instrument(fund)!!.revision, 100_000))
        val operation = testOperationId()
        commands.execute(UpdateInstrumentPrices(operation, change, recordUnchanged = true))
        try {
            commands.execute(UpdateInstrumentPrices(operation, change, recordUnchanged = false))
            fail("A different request under the same operation id")
        } catch (error: DomainException) { assertEquals(ErrorCode.OPERATION_CONFLICT, error.code) }
        // Replaying the same request returns the first result without a second record.
        commands.execute(UpdateInstrumentPrices(operation, change, recordUnchanged = true))
        assertEquals(2, db.statistics().prices().count { it.instrument_id == fund })
    }
}
