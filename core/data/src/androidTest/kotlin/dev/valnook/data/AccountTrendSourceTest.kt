package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.valnook.data.database.*
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.time.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * One account's trend reads only that account's rows, and every account's trades of the instruments it holds: with no
 * manual price, a holding is valued at the latest trade of its instrument, whichever account made it.
 */
class AccountTrendSourceTest {
    private lateinit var db: ValnookDatabase
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), zone)
    private val today = LocalDate.now(clock)
    private var a = 0L
    private var b = 0L
    private var c = 0L

    private fun at(day: LocalDate, hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        val start = at(today.minusDays(10), 0)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(start, today.minusDays(10).toEpochDay()))
        db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        fun account(name: String) = runBlocking { db.accounts().insert_account(AccountEntity(name = name, note = "",
            created_at_ms = start, updated_at_ms = start)) }
        a = account("A"); b = account("B"); c = account("C")
        db.cash().insert_cash(CashEntity(a, "CNY", 1_000, 1, start, name = "a", created_at_ms = start))
        db.cash().insert_cash(CashEntity(c, "CNY", 5_000, 1, start, name = "c", created_at_ms = start))
        val type = db.instruments().insert_type(TypeEntity(name = "Fund", normalized_name = "fund",
            created_at_ms = start, updated_at_ms = start))
        val instrument = db.instruments().insertInstrument(InstrumentEntity(asset_type_id = type, name = "Shared",
            symbol = "SH", currency_code = "CNY", current_price_e5 = 13_000_000, currency_locked = true,
            revision = 1, price_updated_at_ms = start, created_at_ms = start, updated_at_ms = start))
        // A buys first at 100; B buys the same instrument later at 130. No manual price exists.
        listOf(Triple(a, today.minusDays(5), 10_000_000_000L), Triple(b, today.minusDays(3), 13_000_000_000L))
            .forEachIndexed { index, (account, day, price) ->
                val position = db.positions().insert_investment(InvestmentEntity(savings_account_id = account,
                    instrument_id = instrument, holding_quantity_e8 = 100_000_000, revision = 1,
                    created_at_ms = start, updated_at_ms = start, remaining_cost = "100", realized_profit = "0",
                    chronology_valid = true, algorithm_version = 5, position_state = "HOLDING", last_activity_at_ms = start))
                db.operations().insert_operation(OperationEntity("buy-$index", "BUY", "test", null, null, start))
                db.ledger().insert_trade(TradeEntity(investment_id = position, operation_id = "buy-$index",
                    direction = Direction.BUY.name, quantity_e8 = 100_000_000, execution_price_e8 = price,
                    amount_minor = price / 1_000_000, currency_code = "CNY", cash_linked = false,
                    occurred_at_ms = at(day, 10), created_at_ms = start, updated_at_ms = start))
            }
    }

    @After fun close() = db.close()

    private fun value(trend: List<StatisticsPoint>, day: LocalDate) = requireNotNull(trend.single { it.date == day }.value)

    @Test fun a_holding_is_priced_by_another_accounts_later_trade() = runBlocking {
        val trend = RoomStatistics(db, clock).loadAccountTrend(a)
        // Cash 10 plus one unit: at A's own price, then at B's.
        assertEquals(0, BigDecimal("10").compareTo(value(trend, today.minusDays(6))))
        assertEquals(0, BigDecimal("110").compareTo(value(trend, today.minusDays(5))))
        assertEquals(0, BigDecimal("110").compareTo(value(trend, today.minusDays(4))))
        assertEquals(0, BigDecimal("140").compareTo(value(trend, today.minusDays(3))))
        assertEquals(0, BigDecimal("140").compareTo(value(trend, today.minusDays(1))))
    }

    @Test fun the_accounts_add_up_to_the_whole_book_every_past_day() = runBlocking {
        val statistics = RoomStatistics(db, clock)
        val trends = listOf(a, b, c).map { statistics.loadAccountTrend(it) }
        val book = listOf(YearMonth.from(today.minusDays(10)), YearMonth.from(today)).distinct().flatMap { month ->
            statistics.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
                StatisticsPeriod(StatisticsGranularity.DAILY, month.year, month.monthValue))).points
        }.filter { it.reliable && it.date < today && it.date >= today.minusDays(10) }
        assertEquals(10, book.size)
        book.forEach { point ->
            val sum = trends.map { value(it, point.date) }.reduce(BigDecimal::add)
            assertEquals("On ${point.date}", 0, sum.compareTo(point.value))
        }
    }
}
