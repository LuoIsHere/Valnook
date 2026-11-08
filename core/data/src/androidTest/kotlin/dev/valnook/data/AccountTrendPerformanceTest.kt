package dev.valnook.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.FxRateEntity
import dev.valnook.data.database.SettingsEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.domain.model.StatisticsGranularity
import dev.valnook.domain.model.StatisticsMetric
import dev.valnook.domain.model.StatisticsPeriod
import dev.valnook.domain.model.StatisticsPoint
import dev.valnook.domain.model.StatisticsRequest
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in (`-e statisticsTiming true`): a large synthetic book of ten accounts, timing one account's trend and a cold
 * whole-book series, with a checksum of every value so a change to how they are worked out can show the same results.
 * Every day, the accounts' trends add up to the whole book.
 */
@LargeTest
class AccountTrendPerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-10-03T04:00:00Z"), zone)
    private val today = LocalDate.now(clock)
    private val first = today.minusDays(120)
    private val accounts = 1..10L
    private val instruments = 1..20L

    private fun seed(db: ValnookDatabase) = runBlocking {
        val start = first.atStartOfDay(zone).toInstant().toEpochMilli()
        val span = clock.millis() - start - 60_000
        val sql = db.openHelper.writableDatabase
        sql.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(start, first.toEpochDay()))
        db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        db.overview().insertRates(listOf(FxRateEntity("USD", "CNY", "7.1", 1)))
        val random = Random(42)
        db.withTransaction {
            sql.execSQL("INSERT INTO asset_types(id,name,normalized_name,created_at_ms,updated_at_ms) VALUES (100,'合成','合成',?,?)", arrayOf(start, start))
            instruments.forEach { id ->
                sql.execSQL("""INSERT INTO instruments(id,asset_type_id,name,symbol,currency_code,current_price_e5,currency_locked,revision,
                    symbol_locked,price_updated_at_ms,created_at_ms,updated_at_ms) VALUES (?,100,?,?,?,100000,1,1,0,?,?,?)""",
                    arrayOf(id, "合成品种 $id", "SYN$id", if (id % 2 == 0L) "USD" else "CNY", start, start, start))
            }
            val price = sql.compileStatement("""INSERT INTO instrument_price_history(instrument_id,price_e5,currency_code,effective_at_ms,
                created_at_ms,updated_at_ms,revision,is_deleted) VALUES (?,?,?,?,?,?,1,0)""")
            instruments.forEach { id ->
                (0 until 120).forEach { day ->
                    price.clearBindings(); price.bindLong(1, id); price.bindLong(2, 90_000L + random.nextInt(20_000))
                    price.bindString(3, if (id % 2 == 0L) "USD" else "CNY")
                    val at = start + day * 86_400_000L + 43_200_000L
                    price.bindLong(4, at); price.bindLong(5, at); price.bindLong(6, at); price.executeInsert()
                }
            }
            // Every row has an operation of its own, as the commands give it.
            val operation = sql.compileStatement("INSERT INTO operations(operation_id,kind,request_fingerprint,created_at_ms) VALUES (?,'SEED','seed',?)")
            var operations = 0
            fun nextOperation(at: Long): String {
                val id = "seed-${++operations}"
                operation.clearBindings(); operation.bindString(1, id); operation.bindLong(2, at); operation.executeInsert()
                return id
            }
            val entry = sql.compileStatement("""INSERT INTO cash_entries(original_operation_id,savings_account_id,currency_code,cash_account_id,
                source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms)
                VALUES (?,?,?,?,'CASH_SET',NULL,?,?,'',1,0,?,?)""")
            val trade = sql.compileStatement("""INSERT INTO investment_trades(investment_id,operation_id,direction,quantity_e8,
                execution_price_e8,amount_minor,currency_code,cash_linked,cash_account_id,occurred_at_ms,created_at_ms,revision,
                is_deleted,updated_at_ms,fee_minor) VALUES (?,?,?,?,?,?,?,0,NULL,?,?,1,0,0,0)""")
            accounts.forEach { account ->
                sql.execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms) VALUES (?,?,'',?,?)",
                    arrayOf(account, "合成账户 $account", start, start))
                listOf("CNY", "USD").forEachIndexed { index, code ->
                    val cash = account * 10 + index + 1
                    sql.execSQL("""INSERT INTO cash_accounts(id,savings_account_id,currency_code,balance_minor,revision,updated_at_ms,
                        name,note,currency_locked,created_at_ms) VALUES (?,?,?,?,1,?,?,'',1,?)""",
                        arrayOf(cash, account, code, 50_000_000L + random.nextInt(1_000_000), start, code, start))
                    repeat(2_000) {
                        val at = start + (random.nextDouble() * span).toLong()
                        val op = nextOperation(at)
                        entry.clearBindings(); entry.bindString(1, op); entry.bindLong(2, account); entry.bindString(3, code)
                        entry.bindLong(4, cash); entry.bindLong(5, random.nextInt(200_000) - 100_000L); entry.bindLong(6, at)
                        entry.bindLong(7, at); entry.bindLong(8, at); entry.executeInsert()
                    }
                }
                sql.execSQL("""INSERT INTO term_deposits(id,savings_account_id,currency_code,principal_minor,annual_rate_percent_e8,
                    start_epoch_day,end_epoch_day,interest_rule,calculation_version,rounding_mode,expected_interest_minor,status,
                    open_cash_linked,open_operation_id,created_at_ms,updated_at_ms) VALUES (?,?,'CNY',?,200000000,?,?,
                    'ACTUAL_365',1,'HALF_UP',0,'OPEN',0,?,?,?)""",
                    arrayOf(account, account, 1_000_000L * account, first.toEpochDay() + account * 5, first.toEpochDay() + 365,
                        nextOperation(start), start, start))
                (1..3L).forEach { k ->
                    val position = account * 10 + k
                    val instrument = (account + k) % 20 + 1
                    val code = if (instrument % 2 == 0L) "USD" else "CNY"
                    sql.execSQL("""INSERT INTO investments(id,savings_account_id,instrument_id,opening_quantity_e8,holding_quantity_e8,
                        revision,created_at_ms,updated_at_ms,opening_at_ms,chronology_valid,algorithm_version)
                        VALUES (?,?,?,0,0,1,?,?,0,1,5)""", arrayOf(position, account, instrument, start, start))
                    repeat(300) { index ->
                        val at = start + (random.nextDouble() * span).toLong()
                        val sell = index % 4 == 3
                        val op = nextOperation(at)
                        trade.clearBindings(); trade.bindLong(1, position); trade.bindString(2, op); trade.bindString(3, if (sell) "SELL" else "BUY")
                        trade.bindLong(4, (if (sell) 50L else 100L) * 100_000_000L); trade.bindLong(5, 100_000_000L + random.nextInt(20_000_000))
                        trade.bindLong(6, 10_000L); trade.bindString(7, code); trade.bindLong(8, at); trade.bindLong(9, at)
                        trade.executeInsert()
                    }
                }
            }
        }
    }

    private fun median(values: List<Long>) = values.sorted()[values.size / 2]
    private fun plain(value: BigDecimal?) = value?.stripTrailingZeros()?.toPlainString() ?: "null"

    @Test fun one_accounts_trend_on_a_large_book() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("statisticsTiming") == "true")
        val name = "trend-performance-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, ValnookDatabase::class.java, name)
            .addCallback(ValnookDatabase.seed).addCallback(ValnookDatabase.defaults).build()
        try {
            seed(db)
            val statistics = RoomStatistics(db, clock)
            statistics.loadAccountTrend(1)
            val trendMs = (1..5).map {
                val started = System.nanoTime(); statistics.loadAccountTrend(1); (System.nanoTime() - started) / 1_000_000
            }
            val trends = accounts.associateWith { statistics.loadAccountTrend(it) }

            db.statistics().clearCache()
            db.statistics().invalidate(first.toEpochDay())
            val started = System.nanoTime()
            var month = YearMonth.from(first)
            val book = mutableMapOf<LocalDate, StatisticsPoint>()
            while (month <= YearMonth.from(today)) {
                statistics.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
                    StatisticsPeriod(StatisticsGranularity.DAILY, month.year, month.monthValue))).points
                    .filter { it.reliable && it.date < today }.forEach { book[it.date] = it }
                month = month.plusMonths(1)
            }
            val bookMs = (System.nanoTime() - started) / 1_000_000

            // The accounts add up to the whole book on every past day of the trend.
            val history = trends.getValue(1).map { it.date }.filter { it < today && it >= first }
            history.forEach { date ->
                val sum = accounts.sumOf { account -> trends.getValue(account).single { it.date == date }.value ?: BigDecimal.ZERO }
                assertEquals("Accounts against the whole book on $date", 0, sum.compareTo(book.getValue(date).value))
            }
            val digest = MessageDigest.getInstance("SHA-256")
            accounts.forEach { account -> trends.getValue(account).forEach { digest.update("$account ${it.date} ${plain(it.value)} ${it.complete}\n".toByteArray()) } }
            book.toSortedMap().forEach { (date, point) -> digest.update("book $date ${plain(point.value)}\n".toByteArray()) }
            val result = JSONObject().put("trendMedianMs", median(trendMs)).put("trendRunsMs", trendMs.toString())
                .put("bookColdMs", bookMs).put("checkedDays", history.size)
                .put("checksum", digest.digest().joinToString("") { "%02x".format(it) })
            Log.i("TrendPerformance", result.toString())
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("trend_performance", result.toString()) })
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
