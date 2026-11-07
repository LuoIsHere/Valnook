package dev.valnook.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.database.ensureDefaultAssetType
import dev.valnook.data.repository.RoomDataMaintenance
import dev.valnook.data.repository.RoomInvestments
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.AssetTypeRules
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.repository.DeleteAssetType
import dev.valnook.domain.repository.SaveAssetType
import dev.valnook.domain.money.DecimalRules
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** The built-in "uncategorized" class: always there, once, last, never renamed or imitated; deleted classes empty into it. */
@RunWith(AndroidJUnit4::class)
class DefaultAssetTypeTest {
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private lateinit var investments: RoomInvestments
    private val clock = Clock.fixed(Instant.parse("2026-11-08T12:00:00Z"), ZoneOffset.UTC)
    private fun id() = UUID.randomUUID().toString()
    private val sql get() = db.openHelper.writableDatabase

    @Before fun prepare() {
        db = ValnookDatabase.inMemory(ApplicationProvider.getApplicationContext())
        commands = RoomFinancialCommands(db, clock)
        investments = RoomInvestments(db)
    }
    @After fun close() { db.close() }

    private fun expect(code: ErrorCode, block: suspend () -> Unit) = runBlocking {
        try { block(); fail("expected $code") } catch (error: DomainException) { assertEquals(code, error.code) }
    }
    private fun defaults(): Int = sql.query("SELECT COUNT(*) FROM asset_types WHERE normalized_name=?",
        arrayOf(AssetTypeRules.DEFAULT_KEY)).use { it.moveToFirst(); it.getInt(0) }

    @Test fun a_new_database_has_one_default_class_listed_after_the_users_own() = runBlocking {
        commands.execute(SaveAssetType(id(), null, "债券"))
        commands.execute(SaveAssetType(id(), null, "Alpha"))
        val types = investments.observe_types().first()
        assertEquals(listOf("alpha", "债券", AssetTypeRules.DEFAULT_NAME).map { it.lowercase() }, types.map { it.name.lowercase() })
        assertEquals(listOf(false, false, true), types.map { it.isDefault })
        // Opening again adds nothing.
        ensureDefaultAssetType(sql, clock.millis())
        assertEquals(1, defaults())
    }

    @Test fun the_default_keeps_its_name_and_no_class_may_take_it() {
        val default = runBlocking { investments.observe_types().first().single { it.isDefault } }
        expect(ErrorCode.DEFAULT_TYPE) { commands.execute(SaveAssetType(id(), default.id, "其他")) }
        for (name in listOf("未分类", " Uncategorized ", "UNCATEGORIZED", AssetTypeRules.DEFAULT_KEY))
            expect(ErrorCode.DUPLICATE_TYPE) { commands.execute(SaveAssetType(id(), null, name)) }
        val own = runBlocking { commands.execute(SaveAssetType(id(), null, "基金")).id }
        expect(ErrorCode.DUPLICATE_TYPE) { commands.execute(SaveAssetType(id(), own, "未分类")) }
        runBlocking { assertEquals(AssetTypeRules.DEFAULT_NAME, investments.observe_types().first().single { it.isDefault }.name) }
    }

    @Test fun a_class_the_user_already_called_uncategorized_becomes_the_default() = runBlocking {
        sql.execSQL("DELETE FROM asset_types")
        sql.execSQL("INSERT INTO asset_types(id,name,normalized_name,created_at_ms,updated_at_ms) VALUES (7,'Uncategorized','uncategorized',1,1)")
        sql.execSQL("INSERT INTO asset_types(id,name,normalized_name,created_at_ms,updated_at_ms) VALUES (8,'股票','股票',1,1)")
        ensureDefaultAssetType(sql, clock.millis())
        val types = investments.observe_types().first()
        assertEquals(2, types.size)
        val default = types.single { it.isDefault }
        assertEquals(7L, default.id)
        assertEquals(AssetTypeRules.DEFAULT_NAME, default.name)
    }

    @Test fun clearing_all_data_leaves_only_the_default_class() = runBlocking {
        commands.execute(SaveAssetType(id(), null, "基金"))
        RoomDataMaintenance(db).clearBusinessData()
        val types = investments.observe_types().first()
        assertEquals(1, types.size)
        assertTrue(types.single().isDefault)
    }

    private fun count(query: String, vararg args: Any): Long = sql.query(query, args).use { it.moveToFirst(); it.getLong(0) }

    @Test fun deleting_a_class_moves_its_instruments_to_the_default_and_keeps_their_records() = runBlocking {
        val account = commands.testAccount("合成账户")
        val funds = commands.testType("合成基金")
        val other = commands.testType("合成股票")
        val held = commands.testInvestment(account, "合成基金甲", "SYNA", funds, "CNY", DecimalRules.parse_e8("2"),
            DecimalRules.parse_e8("10"), DecimalRules.parse_e8("10"))
        val kept = commands.testInvestment(account, "合成股票乙", "SYNB", other, "CNY", 0, DecimalRules.parse_e8("5"), null)
        val instrument = db.ledger().investment(held)!!.instrument_id
        val revision = db.instruments().instrument(instrument)!!.revision
        val trades = count("SELECT COUNT(*) FROM investment_trades")
        val operation = id()
        commands.execute(DeleteAssetType(operation, funds))
        val default = investments.observe_types().first().single { it.isDefault }
        assertEquals(default.id, db.instruments().instrument(instrument)!!.asset_type_id)
        assertEquals(revision + 1, db.instruments().instrument(instrument)!!.revision)
        assertEquals(other, db.instruments().instrument(db.ledger().investment(kept)!!.instrument_id)!!.asset_type_id)
        assertEquals(listOf("合成股票", AssetTypeRules.DEFAULT_NAME), investments.observe_types().first().map { it.name })
        assertEquals(trades, count("SELECT COUNT(*) FROM investment_trades"))
        assertEquals(DecimalRules.parse_e8("2"), db.ledger().investment(held)!!.holding_quantity_e8)
        assertEquals(1L, count("SELECT COUNT(*) FROM audit_events WHERE action='DELETE' AND entity_kind='ASSET_TYPE' AND entity_id=?", funds.toString()))
        // The same request again is answered from its receipt, not refused for a class that is gone.
        assertEquals(funds, commands.execute(DeleteAssetType(operation, funds)).id)
    }

    @Test fun the_default_class_and_missing_ones_cannot_be_deleted() {
        val default = runBlocking { investments.observe_types().first().single { it.isDefault } }
        expect(ErrorCode.DEFAULT_TYPE) { commands.execute(DeleteAssetType(id(), default.id)) }
        expect(ErrorCode.NOT_FOUND) { commands.execute(DeleteAssetType(id(), 999)) }
        assertEquals(1, defaults())
    }

    @Test fun a_database_opened_without_the_callback_gets_the_default_when_a_class_is_deleted() = runBlocking {
        val bare = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        try {
            val bareCommands = RoomFinancialCommands(bare, clock)
            val type = bareCommands.testType("合成类型")
            val instrument = bareCommands.testInstrument("合成标的", "SYNC", type, "CNY", DecimalRules.parse_e8("1"))
            bareCommands.execute(DeleteAssetType(id(), type))
            val types = RoomInvestments(bare).observe_types().first()
            assertEquals(1, types.size)
            assertEquals(types.single().id, bare.instruments().instrument(instrument)!!.asset_type_id)
            assertTrue(types.single().isDefault)
        } finally { bare.close() }
    }
}
