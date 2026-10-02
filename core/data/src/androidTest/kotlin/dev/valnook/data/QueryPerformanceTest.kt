package dev.valnook.data

import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.LargeTest
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** Disk fixtures measure production paths; command correctness is tested separately. */
@LargeTest
class QueryPerformanceTest {
    private data class Fixture(val label: String, val accounts: Int, val instruments: Int,
        val trades: Int, val hotspot: Boolean = false)
    private data class Query(val sql: String, val args: List<Any?>)
    private val captured = CopyOnWriteArrayList<Query>()
    private val roomMetadataReads = AtomicInteger()
    @Volatile private var recording = false
    private val clock = Clock.systemUTC()
    private fun positionCount(fixture: Fixture) = if (fixture.hotspot) 1 else
        (fixture.instruments - maxOf(1, fixture.instruments / 100)) * 2

    private fun open(context: Context, name: String): ValnookDatabase =
        Room.databaseBuilder(context, ValnookDatabase::class.java, name)
            .addCallback(ValnookDatabase.seed)
            .setQueryCallback({ sql, args ->
                if (recording && sql.trimStart().startsWith("SELECT", true)) {
                    if (sql.contains("room_", true)) roomMetadataReads.incrementAndGet()
                    else captured.add(Query(sql, args.toList()))
                }
            }, Executor { it.run() }).build()

    private fun operations(sql: SupportSQLiteDatabase, count: Int, prefix: String, kind: String, resultOffset: Int = 0) {
        sql.compileStatement("INSERT INTO operations VALUES (?,?,'synthetic',?,?,0)").use { statement ->
            for (id in 1..count) {
                statement.bindString(1, "$prefix-$id")
                statement.bindString(2, kind)
                statement.bindString(3, kind)
                statement.bindLong(4, (id + resultOffset).toLong())
                statement.executeInsert()
            }
        }
    }

    private suspend fun populate(db: ValnookDatabase, fixture: Fixture) = db.withTransaction {
        val sql = db.openHelper.writableDatabase
        val random = Random(20261001)
        for (id in 1..fixture.accounts) {
            sql.execSQL("INSERT INTO savings_accounts VALUES (?,?,'synthetic',0,0,1)", arrayOf<Any>(id, "fixture-account-$id"))
            sql.execSQL("INSERT INTO cash_accounts VALUES (?,'CNY',100000000,100,0,?,'CNY fixture','synthetic',1,0)",
                arrayOf<Any>(id, id))
        }
        sql.execSQL("INSERT INTO asset_types VALUES (1,'ETF','etf',0,0)")
        sql.execSQL("INSERT INTO app_settings VALUES (1,'CNY',1,'SYSTEM','GREEN_GAIN')")
        sql.execSQL("INSERT INTO fx_rates VALUES ('USD','CNY','7.2',0)")
        for (id in 1..fixture.instruments) {
            val associated = if (fixture.hotspot) 1 else positionCount(fixture) / 2
            sql.execSQL("INSERT INTO instruments VALUES (?,1,?,?,'USD',18000000,?,1,0,0,0,0)",
                arrayOf<Any>(id, "fixture-${id.toString().padStart(5, '0')}", "F$id", if (id <= associated) 1 else 0))
        }
        val positions = positionCount(fixture)
        val quantities = IntArray(positions)
        val costsMinor = LongArray(positions)
        val realizedMinor = LongArray(positions)
        val lastActivity = LongArray(positions)
        for (id in 1..positions) {
            val associated = if (fixture.hotspot) 1 else positions / 2
            val instrumentId = (id - 1) % associated + 1
            val accountId = if (fixture.hotspot) 1 else (instrumentId - 1 + (id - 1) / associated) % fixture.accounts + 1
            sql.execSQL("""INSERT INTO investments(id,savings_account_id,instrument_id,
                opening_quantity_e8,holding_quantity_e8,revision,created_at_ms,updated_at_ms,
                opening_cost_price_e8,opening_at_ms,remaining_cost,realized_profit,chronology_valid,
                algorithm_version,position_state,last_activity_at_ms)
                VALUES (?,?,?,0,0,1,0,0,NULL,0,'0','0',1,${InvestmentProfitCalculator.ALGORITHM_VERSION},'PENDING',0)""",
                arrayOf<Any>(id, accountId, instrumentId))
        }
        operations(sql, fixture.trades, "trade", "INVESTMENT_TRADE")
        sql.compileStatement("""INSERT INTO investment_trades(id,investment_id,operation_id,direction,
            quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,cash_account_id,occurred_at_ms,
            created_at_ms,revision,is_deleted,updated_at_ms) VALUES (?,?,?,?,100000000,?,?,'USD',0,NULL,?,0,1,?,0)""").use { statement ->
            for (id in 1..fixture.trades) {
                val position = if (fixture.hotspot) 1 else (id - 1) % positions + 1
                val sell = fixture.hotspot && id % 2 == 0
                val deleted = !fixture.hotspot && id % 10 == 0
                val priceMinor = if (sell) 12000L else (10000 + random.nextInt(0, 10) * 100).toLong()
                if (!deleted) {
                    lastActivity[position - 1] = if (fixture.hotspot) ((id + 1) / 2).toLong() else id.toLong()
                    quantities[position - 1] += if (sell) -1 else 1
                    if (sell) {
                        realizedMinor[position - 1] += priceMinor - costsMinor[position - 1]
                        costsMinor[position - 1] = 0
                    } else costsMinor[position - 1] += priceMinor
                }
                statement.bindLong(1, id.toLong())
                statement.bindLong(2, position.toLong())
                statement.bindString(3, "trade-$id")
                statement.bindString(4, if (sell) "SELL" else "BUY")
                statement.bindLong(5, priceMinor * 1000000L)
                statement.bindLong(6, priceMinor)
                statement.bindLong(7, if (fixture.hotspot) ((id + 1) / 2).toLong() else id.toLong())
                statement.bindLong(8, if (deleted) 1 else 0)
                statement.executeInsert()
            }
        }
        quantities.forEachIndexed { index, quantity ->
            val cost = BigDecimal.valueOf(costsMinor[index], 2).toPlainString()
            val realized = BigDecimal.valueOf(realizedMinor[index], 2).toPlainString()
            sql.execSQL("""UPDATE investments SET holding_quantity_e8=?,remaining_cost=?,realized_profit=?,
                position_state=?,last_activity_at_ms=? WHERE id=?""",
                arrayOf<Any>(quantity * 100000000L, cost, realized,
                    if (quantity > 0) "HOLDING" else if (fixture.hotspot) "CLOSED" else "PENDING", lastActivity[index], index + 1))
        }
        val entries = fixture.accounts * 100
        operations(sql, fixture.accounts, "usd-cash", "CASH_ENTRY", entries)
        for (id in 1..fixture.accounts) {
            val cashId = fixture.accounts + id
            sql.execSQL("INSERT INTO cash_accounts VALUES (?,'USD',10000,1,0,?,'USD fixture','synthetic',1,0)",
                arrayOf<Any>(id, cashId))
            sql.execSQL("""INSERT INTO cash_entries(id,original_operation_id,savings_account_id,currency_code,
                cash_account_id,source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms)
                VALUES (?,?,?,'USD',?,'CASH_SET',NULL,10000,0,'',1,0,0,0)""",
                arrayOf<Any>(entries + id, "usd-cash-$id", id, cashId))
            sql.execSQL("""INSERT INTO cash_movements(id,operation_id,savings_account_id,currency_code,
                cash_account_id,reason,delta_minor,balance_before_minor,balance_after_minor,created_at_ms)
                VALUES (?,?,?,'USD',?,'CASH_SET',10000,0,10000,0)""",
                arrayOf<Any>(entries + id, "usd-cash-$id", id, cashId))
        }
        operations(sql, entries, "cash", "CASH_ENTRY")
        sql.compileStatement("""INSERT INTO cash_entries(id,original_operation_id,savings_account_id,currency_code,
            cash_account_id,source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms)
            VALUES (?,?,?,'CNY',?,'CASH_SET',NULL,?,?,'',1,0,0,0)""").use { statement ->
            for (id in 1..entries) {
                statement.bindLong(1, id.toLong())
                statement.bindString(2, "cash-$id")
                val accountId = ((id - 1) / 100 + 1).toLong()
                statement.bindLong(3, accountId)
                statement.bindLong(4, accountId)
                statement.bindLong(5, 1000000)
                statement.bindLong(6, id.toLong())
                statement.executeInsert()
            }
        }
        sql.compileStatement("""INSERT INTO cash_movements(id,operation_id,savings_account_id,currency_code,
            cash_account_id,reason,delta_minor,balance_before_minor,balance_after_minor,created_at_ms)
            VALUES (?,?,?,'CNY',?,'CASH_SET',1000000,?,?,0)""").use { statement ->
            for (id in 1..entries) {
                statement.bindLong(1, id.toLong())
                statement.bindString(2, "cash-$id")
                val accountId = ((id - 1) / 100 + 1).toLong()
                statement.bindLong(3, accountId)
                statement.bindLong(4, accountId)
                val before = (id - 1) % 100 * 1000000L
                statement.bindLong(5, before)
                statement.bindLong(6, before + 1000000)
                statement.executeInsert()
            }
        }
        // 100 synthetic entries exactly match each account's persisted balance.
        operations(sql, fixture.accounts * 2, "deposit", "TERM_DEPOSIT")
        for (id in 1..fixture.accounts * 2) {
            sql.execSQL("""INSERT INTO term_deposits(id,savings_account_id,currency_code,principal_minor,
                annual_rate_percent_e8,start_epoch_day,end_epoch_day,expected_interest_minor,
                interest_rule,calculation_version,rounding_mode,status,
                open_cash_linked,open_operation_id,created_at_ms,updated_at_ms)
                VALUES (?,?,?,1000000,300000000,20000,20365,30000,'ACT_365F_SIMPLE',1,'HALF_UP','OPEN',0,?,0,0)""",
                arrayOf<Any>(id, (id - 1) % fixture.accounts + 1, if (id % 2 == 0) "USD" else "CNY", "deposit-$id"))
        }
        sql.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        sql.query("""SELECT COUNT(*) FROM cash_accounts b WHERE b.balance_minor !=
            (SELECT COALESCE(SUM(e.delta_minor),0) FROM cash_entries e WHERE e.cash_account_id=b.id AND e.is_deleted=0)
            OR b.balance_minor != (SELECT COALESCE(SUM(m.delta_minor),0) FROM cash_movements m WHERE m.cash_account_id=b.id)""").use {
            assertTrue(it.moveToFirst())
            assertEquals("Synthetic ledger imbalance", 0, it.getInt(0))
        }
    }

    private suspend fun measure(db: ValnookDatabase, name: String, expectedRows: Int,
        action: suspend () -> Int): JSONObject {
        val samples = ArrayList<Double>()
        val counts = ArrayList<Int>()
        val metadataCounts = ArrayList<Int>()
        captured.clear()
        recording = true
        var first = 0.0
        try {
            repeat(31) { repeat ->
                val before = captured.size
                val metadataBefore = roomMetadataReads.get()
                val started = System.nanoTime()
                assertEquals("$name rows", expectedRows, action())
                val elapsed = (System.nanoTime() - started) / 1_000_000.0
                counts.add(captured.size - before)
                metadataCounts.add(roomMetadataReads.get() - metadataBefore)
                if (repeat == 0) first = elapsed else samples.add(elapsed)
            }
        } finally { recording = false }
        samples.sort()
        val plans = JSONArray()
        captured.distinct().forEach { query ->
            val plan = JSONArray()
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN " + query.sql, query.args.toTypedArray()).use {
                while (it.moveToNext()) plan.put(it.getString(3))
            }
            plans.put(JSONObject().put("sql", query.sql).put("bindings", JSONArray(query.args)).put("plan", plan))
        }
        val history = captured.filter { it.sql.contains("FROM investment_trades", true) }
        if (name.contains("overview") || name == "catalog" || name == "position-detail" ||
            name == "instrument-summary" || name == "account-associations" || name == "investment-grouping")
            assertTrue("Unexpected history replay: $name", history.isEmpty())
        if (name == "dao-overview" || name == "repository-overview")
            assertTrue("N+1 snapshot: $counts", counts.all { it == 7 })
        if (name == "historical-price-correction" || name == "append-trade") {
            val replay = history.filter { it.sql.contains("ORDER BY occurred_at_ms,id") }
            assertEquals(31, replay.size)
            assertTrue(replay.all { it.args.first() == 1L })
        }
        return JSONObject().put("path", name).put("returned_rows", expectedRows)
            .put("repetitions", 30).put("first_read_ms", first).put("p50_ms", samples[14])
            .put("p95_ms", samples[28]).put("business_select_counts", JSONArray(counts))
            .put("room_metadata_select_counts", JSONArray(metadataCounts)).put("plans", plans)
    }

    @Test fun persistent_production_paths_small_wide_and_single_position_hotspot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val datasets = JSONArray()
        val fixtures = listOf(Fixture("small", 5, 20, 1000), Fixture("wide", 50, 2000, 100000),
            Fixture("hotspot", 1, 1, 50000, true))
        var sqliteVersion = ""
        try {
            for (fixture in fixtures) {
                val name = "perf-${fixture.label}-${UUID.randomUUID()}.db"
                var db = open(context, name)
                try {
                    populate(db, fixture)
                    db.close()
                    db = open(context, name)
                    db.openHelper.readableDatabase.query("SELECT sqlite_version()").use {
                        assertTrue(it.moveToFirst())
                        sqliteVersion = it.getString(0)
                    }
                    val overview = RoomOverview(db)
                    val commands = RoomFinancialCommands(db, clock)
                    val investments = RoomInvestments(db)
                    val instruments = RoomInstruments(db)
                    val cash = RoomCash(db.cash())
                    val deposits = RoomDeposits(db.deposits())
                    val settings = RoomSettings(db, clock)
                    val positions = positionCount(fixture)
                    val results = JSONArray()
                    results.put(measure(db, "dao-overview", positions) { db.overview().snapshot().positions.size })
                    results.put(measure(db, "repository-overview", positions) {
                        val snapshot = overview.snapshot()
                        assertTrue(AssetValuation.calculate(snapshot).total.complete)
                        snapshot.positions.size
                    })
                    results.put(measure(db, "catalog", fixture.instruments) { instruments.observeInstruments().first().size })
                    results.put(measure(db, "investment-grouping", fixture.accounts) {
                        val snapshot = overview.snapshot()
                        val summary = AssetValuation.calculate(snapshot)
                        val groups = snapshot.positions.groupBy { it.account_id }
                        assertEquals(fixture.instruments, AssetValuation.instrumentSummaries(snapshot).size)
                        assertEquals(positions, groups.values.sumOf { it.size })
                        summary.accounts.size
                    })
                    results.put(measure(db, "instrument-summary", 1) {
                        val summary = AssetValuation.instrumentSummaries(overview.snapshot()).first { it.instrument.id == 1L }
                        val realized = summary.realized!!
                        assertTrue(realized.signum() >= 0)
                        1
                    })
                    val accountRows = overview.snapshot().positions.count { it.account_id == 1L }
                    results.put(measure(db, "account-associations", accountRows) {
                        investments.observe_investments(1, Int.MAX_VALUE, InvestmentSection.ALL).first().size
                    })
                    results.put(measure(db, "position-detail", 1) { if (investments.observe_profit(1).first() != null) 1 else 0 })
                    val firstPage = investments.trade_page(1, null)
                    results.put(measure(db, "trades-first", firstPage.size) { investments.trade_page(1, null).size })
                    val cursor = firstPage.last().let { TradeCursor(it.occurred_at_ms, it.id) }
                    results.put(measure(db, "trades-next", investments.trade_page(1, cursor).size) { investments.trade_page(1, cursor).size })
                    results.put(measure(db, "cash-page", 50) { cash.page(1, "CNY", null, 50).size })
                    results.put(measure(db, "deposit-page", 2) { deposits.page(1, false, null, 50).size })
                    results.put(measure(db, "price-and-overview", positions) {
                        val item = db.instruments().instrument(1)!!
                        commands.execute(SaveInstrument(UUID.randomUUID().toString(), item.id, item.revision,
                            item.name, item.symbol, item.asset_type_id, item.currency_code,
                            if (item.current_price_e5 == 18000000L) 18100000 else 18000000))
                        overview.snapshot().positions.size
                    })
                    results.put(measure(db, "fx-and-overview", positions) {
                        val before = settings.observeSettings().first()
                        val rate = if (before.rates.first().rate.compareTo(BigDecimal("7.2")) == 0) "7.0" else "7.2"
                        settings.applyChange(SaveFinancialSettings(before.revision,
                            requireNotNull(before.baseCurrency),listOf(FxRate(Currency.of("USD"),
                                Currency.of("CNY"),BigDecimal(rate)))))
                        overview.snapshot().positions.size
                    })
                    if (fixture.hotspot) {
                        results.put(measure(db, "historical-price-correction", 1) {
                            val old = investments.get_trade(1)!!
                            commands.execute(EditInvestmentTrade(UUID.randomUUID().toString(), old.id, old.revision,
                                old.direction, old.quantity_e8, if (old.execution_price_e8 == 10000000000L) 10100000000 else 10000000000,
                                old.occurred_at_ms, false))
                            assertEquals(0L, db.positions().investment(1)!!.holding_quantity_e8)
                            1
                        })
                        results.put(measure(db, "append-trade", 1) {
                            commands.execute(RecordAccountTrade(UUID.randomUUID().toString(), 1, 1, Direction.BUY,
                                100000000, 10000000000, 50001 + db.positions().investment(1)!!.revision, false))
                            1
                        })
                    }
                    datasets.put(JSONObject().put("label", fixture.label).put("accounts", fixture.accounts)
                        .put("instruments", fixture.instruments).put("positions", positions).put("trades", fixture.trades)
                        .put("soft_deleted_trades", if (fixture.hotspot) 0 else fixture.trades / 10)
                        .put("cash_entries", fixture.accounts * 101).put("deposits", fixture.accounts * 2)
                        .put("fixture_seed", 20261001).put("measurements", results))
                } finally { db.close(); context.deleteDatabase(name) }
            }
        } finally {
            recording = false
            val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("device", Build.MODEL)
                .put("build", "debug").put("sqlite", sqliteVersion).put("room", "2.8.5")
                .put("database", "isolated on-disk Room; WAL")
                .put("cache", "connection reopened after fixture; first per path then 30 repeats; OS cache uncontrolled")
                .put("comparison", "first v0.0.2 baseline; no historical speedup claim").put("datasets", datasets)
            PlatformTestStorageRegistry.getInstance().openOutputFile("performance.json").use {
                it.write(report.toString(2).toByteArray())
            }
        }
    }
}
