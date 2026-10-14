package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.*
import dev.valnook.data.portability.*
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.Direction
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.RecordInvestmentTrade
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import java.util.zip.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Cost rule 5 books purchases at the unrounded quantity x price; schema 19 and restore re-derive rule 4. */
@RunWith(AndroidJUnit4::class)
class PositionCostRuleTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ValnookDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-13T12:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private lateinit var db: ValnookDatabase

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
    }
    @After fun close() { db.close() }

    private fun e8(value: String) = DecimalRules.parse_e8(value)

    @Test fun v18_to_v19_rebooks_purchases_at_unrounded_notional() {
        val name = "migration-cost-rule-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 18).apply {
                execSQL("INSERT INTO currencies VALUES ('CNY',2)")
                execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms) VALUES (1,'账户','',1,1)")
                execSQL("INSERT INTO asset_types VALUES (1,'基金','基金',1,1)")
                (1..3).forEach {
                    execSQL("INSERT INTO instruments VALUES ($it,1,'标的$it','S$it','CNY',6929400,1,1,1,1,1,1)")
                }
                // Holding: 2.309 x 69.294 settled as 160.00, plus 1 x 10.005 settled as 10.01 with a 0.10 fee.
                execSQL("""INSERT INTO investments VALUES
                    (1,1,1,0,330900000,7,1,1,NULL,0,'170.11','0',1,4,'HOLDING',20),
                    (2,1,2,0,0,3,1,1,NULL,0,'0','0',1,4,'CLOSED',40),
                    (3,1,3,0,0,1,1,1,NULL,0,'0','0',1,4,'PENDING',0)""")
                listOf("b1", "d1", "f1", "b2", "s2").forEach {
                    execSQL("INSERT INTO operations VALUES ('$it','TRADE','fixture','INVESTMENT_TRADE',1,1)")
                }
                execSQL("""INSERT INTO investment_trades VALUES
                    (1,1,'b1','BUY',230900000,6929400000,16000,'CNY',0,NULL,10,1,1,0,1,0),
                    (2,1,'d1','BUY',100000000,100000000,100,'CNY',0,NULL,5,1,2,1,1,0),
                    (3,1,'f1','BUY',100000000,1000500000,1001,'CNY',0,NULL,20,1,1,0,1,10),
                    (4,2,'b2','BUY',230900000,6929400000,16000,'CNY',0,NULL,30,1,1,0,1,0),
                    (5,2,'s2','SELL',230900000,6929400000,16000,'CNY',0,NULL,40,1,1,0,1,0)""")
                close()
            }
            helper.runMigrationsAndValidate(name, 19, true, MIGRATION_18_19).apply {
                query("""SELECT id,remaining_cost,realized_profit,chronology_valid,algorithm_version,
                    holding_quantity_e8,revision,position_state FROM investments ORDER BY id""").use {
                    assertTrue(it.moveToNext())
                    assertEquals("170.104846", it.getString(1)); assertEquals("0", it.getString(2))
                    assertEquals(1, it.getInt(3)); assertEquals(5, it.getInt(4))
                    assertEquals(330900000L, it.getLong(5)); assertEquals(7L, it.getLong(6))
                    assertEquals("HOLDING", it.getString(7))
                    assertTrue(it.moveToNext())
                    assertEquals("0", it.getString(1)); assertEquals("0.000154", it.getString(2))
                    assertEquals(5, it.getInt(4)); assertEquals("CLOSED", it.getString(7))
                    assertTrue(it.moveToNext())
                    assertEquals("0", it.getString(1)); assertEquals("0", it.getString(2))
                    assertEquals(5, it.getInt(4)); assertEquals("PENDING", it.getString(7))
                    assertFalse(it.moveToNext())
                }
                query("SELECT amount_minor FROM investment_trades ORDER BY id").use {
                    val amounts = generateSequence { if (it.moveToNext()) it.getLong(0) else null }.toList()
                    assertEquals(listOf(16000L, 100L, 1001L, 16000L, 16000L), amounts)
                }
                query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun recorded_purchase_keeps_execution_price_as_average_cost() = runBlocking {
        val commands = RoomFinancialCommands(db, clock)
        val position = commands.testInvestment(commands.testAccount("Cost"), "基金", "FUND", commands.testType(),
            "CNY", e8("2.309"), e8("69.294"), e8("69.294"))
        val row = requireNotNull(db.positions().investment(position))
        assertEquals("159.999846", row.remaining_cost)
        assertEquals(InvestmentProfitCalculator.ALGORITHM_VERSION, row.algorithm_version)
        val profit = InvestmentProfitCalculator.fromReadModel(RoomOverview(db).snapshot().positions.single { it.id == position })
        assertEquals(0, BigDecimal("69.294").compareTo(profit.average_cost))
        assertEquals(0, BigDecimal.ZERO.compareTo(profit.unrealized))
    }

    @Test fun rule_four_backup_restores_with_rederived_costs_and_rejects_wrong_declarations() = runBlocking {
        val commands = RoomFinancialCommands(db, clock)
        val account = commands.testAccount("Restore"); val type = commands.testType()
        val holding = commands.testInvestment(account, "持仓", "HOLD", type, "CNY", e8("2.309"), e8("69.294"), e8("69.294"))
        val closed = commands.testInvestment(account, "已清仓", "DONE", type, "CNY", e8("1"), e8("0.333"), e8("0.333"))
        commands.execute(RecordInvestmentTrade(testOperationId(), closed, Direction.SELL, e8("1"), e8("0.333"),
            clock.millis(), false))
        val engine = RoomPortabilityEngine(context, db, clock,
            AppBuildInfo("valnook", "0.0.17", 17, "20261013.1", "test", DATABASE_SCHEMA_VERSION))
        val output = ByteArrayOutputStream(); engine.createBackup(UUID.randomUUID().toString(), output) {}
        val ruleFour = mapOf(holding to ("160" to "0"), closed to ("0" to "0"))
        commands.execute(RecordInvestmentTrade(testOperationId(), holding, Direction.SELL, e8("1"), e8("70"),
            clock.millis(), false))

        restore(engine, rewrite(output.toByteArray(), 4, ruleFour))
        val restoredHolding = requireNotNull(db.positions().investment(holding))
        assertEquals("159.999846", restoredHolding.remaining_cost); assertEquals("0", restoredHolding.realized_profit)
        assertEquals(e8("2.309"), restoredHolding.holding_quantity_e8)
        assertEquals(InvestmentProfitCalculator.ALGORITHM_VERSION, restoredHolding.algorithm_version)
        val restoredClosed = requireNotNull(db.positions().investment(closed))
        assertEquals("0", restoredClosed.remaining_cost); assertEquals("-0.003", restoredClosed.realized_profit)

        val before = db.positions().investment(holding)
        // Rule 4 declarations must still match rule 4, and unknown rules stay incompatible.
        assertTrue(runCatching { restore(engine, rewrite(output.toByteArray(), 4, emptyMap())) }.isFailure)
        assertTrue(runCatching { restore(engine, rewrite(output.toByteArray(), 3, ruleFour)) }.isFailure)
        assertEquals(before, db.positions().investment(holding))
    }

    private suspend fun restore(engine: RoomPortabilityEngine, bytes: ByteArray) {
        val staged = engine.prepareRestore(ByteArrayInputStream(bytes), "test.val_backup") {}
        try { engine.commitRestore(staged) {} } finally { engine.close(staged) }
    }

    /** Re-labels the archive with [rule] and replaces declared costs, keeping hashes consistent. */
    private fun rewrite(bytes: ByteArray, rule: Int, costs: Map<Long, Pair<String, String>>): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; files[entry.name] = zip.readBytes() }
        }
        val path = "data/opening_positions.jsonl"
        files[path] = files.getValue(path).toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }
            .joinToString("\n", postfix = "\n") { line ->
                JSONObject(line).apply {
                    put("algorithm_version", rule.toString())
                    costs[getString("id").toLong()]?.let { (remaining, realized) ->
                        put("remaining_cost", remaining); put("realized_profit", realized)
                    }
                }.toString()
            }.toByteArray()
        val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.getJSONObject("rules").put("positionCost", rule)
        val payloads = manifest.getJSONArray("files")
        repeat(payloads.length()) { index ->
            val file = payloads.getJSONObject(index)
            if (file.getString("path") == path) {
                val content = files.getValue(path)
                file.put("uncompressedBytes", content.size)
                file.put("sha256", java.security.MessageDigest.getInstance("SHA-256").digest(content)
                    .joinToString("") { "%02x".format(it) })
            }
        }
        files["manifest.json"] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                files.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() }
            }
        }.toByteArray()
    }
}
