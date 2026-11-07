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
import dev.valnook.domain.repository.SaveAssetType
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

/** The built-in "uncategorized" class: always there, once, last, and never renamed or imitated. */
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
}
