package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class SharedAssetDatabaseTest {
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private lateinit var instruments: RoomInstruments
    private lateinit var investments: RoomInvestments
    private val queries = CopyOnWriteArrayList<String>()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private var a = 0L
    private var b = 0L
    private var type = 0L
    private fun id() = UUID.randomUUID().toString()
    private fun e(value: String) = R.parse_e8(value)
    private fun decimal(expected: String, actual: BigDecimal) = assertEquals(0, BigDecimal(expected).compareTo(actual))
    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() }).build()
        commands = RoomFinancialCommands(db, clock)
        instruments = RoomInstruments(db, commands)
        investments = RoomInvestments(db, clock)
        a = RoomAccounts(db, clock).save_account(null, "Schwab", "")
        b = RoomAccounts(db, clock).save_account(null, "IBKR", "")
        type = investments.save_type(null, "ETF")
    }
    @After fun close() { db.close() }
    private suspend fun instrument() = commands.execute(SaveInstrument(id(), null, null, "QQQ", "QQQ", type, "USD", 18000000)).id
    private suspend fun trade(account: Long, instrument: Long, direction: Direction, quantity: String, price: String, time: Long, cash: Boolean = false) =
        commands.execute(RecordAccountTrade(id(), account, instrument, direction, e(quantity), e(price), time, cash)).id
    private suspend fun expect(code: ErrorCode, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: DomainException) { assertEquals(code, error.code) }
    }
    private fun consistency() = db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }

    @Test fun account_batch_zero_lock_canonical_retry_and_conflict_are_atomic() = runBlocking {
        val command = SaveAccount(id(), a, 1, "新账户", "新备注",
            listOf(CashBalanceChange("USD", 0, null), CashBalanceChange("CNY", 100000, null)))
        assertEquals(commands.execute(command), commands.execute(command.copy(cashChanges = command.cashChanges.reversed())))
        assertEquals(2L, db.accounts().account(a)!!.revision)
        assertEquals(0L, db.cash().cash_one(a, "USD")!!.balance_minor)
        assertEquals(2, RoomCash(db.cash()).observe_entries(a, "CNY", 10).first().size + RoomCash(db.cash()).observe_entries(a, "USD", 10).first().size)
        val stale = SaveAccount(id(), a, 2, "不得部分保存", "",
            listOf(CashBalanceChange("USD", 100, 1), CashBalanceChange("CNY", 200000, 999)))
        expect(ErrorCode.STALE_BALANCE) { commands.execute(stale) }
        assertEquals("新账户", db.accounts().account(a)!!.name)
        assertEquals(0L, db.cash().cash_one(a, "USD")!!.balance_minor)
        assertNull(commands.operationResult(stale.operation_id))
        expect(ErrorCode.OPERATION_CONFLICT) { commands.execute(command.copy(note = "不同请求")) }
        consistency()
    }
    @Test fun batch_cash_failure_rolls_back_name_all_balances_and_receipt() = runBlocking {
        val command = SaveAccount(id(), a, 1, "全部回滚", "",
            listOf(CashBalanceChange("CNY", 100, null), CashBalanceChange("USD", 200, null)))
        val failing = RoomFinancialCommands(db, clock) { if (it == TransactionPoint.AFTER_CASH) throw java.io.IOException() }
        try { failing.execute(command); fail() } catch (_: java.io.IOException) {}
        assertEquals("Schwab", db.accounts().account(a)!!.name)
        assertNull(db.cash().cash_one(a, "CNY")); assertNull(db.cash().cash_one(a, "USD"))
        assertNull(commands.operationResult(command.operation_id))
        commands.execute(command); assertNotNull(commands.operationResult(command.operation_id))
    }
    @Test fun shared_price_and_independent_costs_match_cross_account_example() = runBlocking {
        val shared = instrument()
        trade(a, shared, Direction.BUY, "10", "100", 1)
        trade(a, shared, Direction.SELL, "5", "120", 2)
        trade(b, shared, Direction.BUY, "10", "200", 1)
        val snapshot = RoomOverview(db).snapshot()
        assertEquals(1, snapshot.instruments.size)
        val first = snapshot.positions.single { it.account_id == a }
        val second = snapshot.positions.single { it.account_id == b }
        decimal("400", investments.observe_profit(first.id).first()!!.unrealized!!)
        decimal("-200", investments.observe_profit(second.id).first()!!.unrealized!!)
        assertEquals(shared, first.instrumentId); assertEquals(shared, second.instrumentId)
        assertNull(db.cash().cash_one(a, "USD")); assertNull(db.cash().cash_one(b, "USD"))
        consistency()
    }
    @Test fun currency_lock_survives_zero_position_and_soft_deleted_history() = runBlocking {
        val shared = instrument()
        val buy = trade(a, shared, Direction.BUY, "1", "100", 1)
        val locked = db.instruments().instrument(shared)!!
        expect(ErrorCode.CURRENCY_LOCKED) { commands.execute(SaveInstrument(id(), shared, locked.revision,
            "QQQ", "QQQ", type, "HKD", 10000000, true)) }
        commands.execute(DeleteInvestmentTrade(id(), buy, 1))
        assertTrue(db.instruments().instrument(shared)!!.currency_locked)
        assertEquals(0L, db.positions().position(a, shared)!!.holding_quantity_e8)
        assertTrue(investments.observe_investments(a, 50, InvestmentSection.ALL).first().isNotEmpty())
        assertTrue(instruments.observeInstruments().first().isNotEmpty())
    }
    @Test fun opening_cost_is_required_and_reentry_preserves_realized_profit() = runBlocking {
        val shared = instrument()
        expect(ErrorCode.FORMAT) { commands.execute(SaveOpeningPosition(id(), a, shared, e("10"), null, 100)) }
        commands.execute(SaveOpeningPosition(id(), a, shared, e("10"), e("100"), 100))
        assertTrue(db.instruments().instrument(shared)!!.currency_locked)
        expect(ErrorCode.HISTORY_CONFLICT) { trade(a, shared, Direction.BUY, "1", "100", 50) }
        trade(a, shared, Direction.SELL, "10", "120", 200)
        trade(a, shared, Direction.BUY, "1", "200", 300)
        val position = db.positions().position(a, shared)!!
        assertEquals("200", position.remaining_cost); assertEquals("200", position.realized_profit)
        val price = db.instruments().instrument(shared)!!
        commands.execute(SaveInstrument(id(), shared, price.revision, "QQQ", "QQQ", type, "USD", 20000000))
        val profit = investments.observe_profit(position.id).first()!!
        assertTrue(profit.cost_complete); assertTrue(profit.realizedComplete); decimal("200", profit.realized!!)
        decimal("0", profit.unrealized!!)
    }
    @Test fun historical_correction_replays_only_affected_position_and_cash_delta_once() = runBlocking {
        val shared = instrument()
        commands.execute(SetCashBalance(id(), a, "USD", 100000, null))
        val buy = trade(a, shared, Direction.BUY, "1", "100", 100, true)
        val sell = trade(a, shared, Direction.SELL, "1", "120", 200, true)
        trade(b, shared, Direction.BUY, "1", "200", 100)
        val other = db.positions().position(b, shared)!!
        expect(ErrorCode.HISTORY_CONFLICT) {
            commands.execute(EditInvestmentTrade(id(), buy, 1, Direction.BUY, e("1"), e("100"), 300, true))
        }
        val correction = EditInvestmentTrade(id(), buy, 1, Direction.BUY, e("1"), e("90"), 100, true)
        commands.execute(correction); commands.execute(correction)
        assertEquals(103000L, db.cash().cash_one(a, "USD")!!.balance_minor)
        assertEquals("30", db.positions().position(a, shared)!!.realized_profit)
        assertEquals(other, db.positions().position(b, shared))
        assertEquals(e("120"), db.trades().trade(sell)!!.execution_price_e8)
        consistency()
    }
    @Test fun price_and_fx_updates_use_read_models_without_history_reads() = runBlocking {
        val shared = instrument()
        trade(a, shared, Direction.BUY, "10", "100", 1)
        trade(a, shared, Direction.SELL, "5", "120", 2)
        val settings = RoomSettings(db, clock)
        settings.saveSettings(AppSettings(Currency.of("CNY"), listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")))), 0)
        decimal("720", AssetValuation.calculate(RoomOverview(db).snapshot()).realized.amount)
        queries.clear()
        val old = db.instruments().instrument(shared)!!
        commands.execute(SaveInstrument(id(), shared, old.revision, "QQQ", "QQQ", type, "USD", 19000000))
        settings.saveSettings(AppSettings(Currency.of("CNY"), listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.0")))), 1)
        val result = RoomOverview(db).snapshot()
        decimal("700", AssetValuation.calculate(result).realized.amount)
        assertFalse(queries.any { it.trimStart().startsWith("SELECT", true) && it.contains("investment_trades", true) })
        assertEquals("100", result.positions.single().realizedProfit)
    }
    @Test fun concurrent_currency_edit_and_first_trade_cannot_create_currency_mismatch() = runBlocking {
        val shared = instrument()
        val results = listOf(async(Dispatchers.IO) { runCatching {
            trade(a, shared, Direction.BUY, "1", "100", 1)
        } }, async(Dispatchers.IO) { runCatching {
            commands.execute(SaveInstrument(id(), shared, 1, "QQQ", "QQQ", type, "HKD", 18000000, true))
        } }).awaitAll()
        assertTrue(results.first().isSuccess)
        val savedInstrument = db.instruments().instrument(shared)!!
        assertTrue(savedInstrument.currency_locked)
        val position = db.positions().position(a, shared)!!
        assertEquals(savedInstrument.currency_code, db.trades().first_trades(position.id, 10).single().currency_code)
        consistency()
    }
    @Test fun persistent_settings_locks_and_costs_survive_reopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "shared-reopen-${id()}.db"
        var disk = Room.databaseBuilder(context, ValnookDatabase::class.java, name).addCallback(ValnookDatabase.seed).build()
        try {
            val account = RoomAccounts(disk, clock).save_account(null, "持久化合成账户", "")
            val type = RoomInvestments(disk, clock).save_type(null, "基金")
            val commands = RoomFinancialCommands(disk, clock)
            val instrument = commands.execute(SaveInstrument(id(), null, null, "QQQ", "QQQ", type, "USD", 10000000)).id
            commands.execute(SaveOpeningPosition(id(), account, instrument, e("1"), e("100"), 1))
            commands.execute(SaveAccount(id(), account, 1, "持久化合成账户", "", listOf(CashBalanceChange("USD", 0, null))))
            RoomSettings(disk, clock).saveSettings(AppSettings(Currency.of("CNY"),
                listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.123456789012")))), 0)
            disk.close()
            disk = Room.databaseBuilder(context, ValnookDatabase::class.java, name).build()
            assertTrue(disk.instruments().instrument(instrument)!!.currency_locked)
            assertEquals("100", disk.positions().position(account, instrument)!!.remaining_cost)
            assertEquals(0L, disk.cash().cash_one(account, "USD")!!.balance_minor)
            val settings = RoomSettings(disk, clock).observeSettings().first()
            assertEquals("7.123456789012", settings.rates.single().rate.toPlainString())
        } finally { disk.close(); context.deleteDatabase(name) }
    }
}
