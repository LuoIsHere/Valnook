package dev.valnook.data.webadmin

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomDeposits
import dev.valnook.data.repository.RoomInvestments
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.domain.webadmin.WebRecordFilter
import dev.valnook.domain.webadmin.WebRecordKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class WebAdminReadRepositoryTest {
    private lateinit var database: ValnookDatabase
    private lateinit var repository: RoomWebAdminReadRepository
    private val clock = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC)

    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        repository = RoomWebAdminReadRepository(database, RoomOverview(database),
            RoomStatistics(database, clock), RoomDeposits(database.deposits()), RoomInvestments(database))
    }

    @After fun close() = database.close()

    @Test fun one_hundred_thousand_trades_are_server_paginated_and_stably_ordered() = runBlocking {
        seedTrades(100_000)
        val started = System.nanoTime()
        val first = repository.records(WebRecordFilter(kind = WebRecordKind.TRADE, pageSize = 100))
        val firstElapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(100, first.items.size)
        assertNotNull(first.nextCursor)
        assertEquals(100_000L, first.items.first().id)
        assertEquals(99_901L, first.items.last().id)
        assertTrue("First 100k page took ${firstElapsedMs}ms", firstElapsedMs < 10_000)

        val second = repository.records(WebRecordFilter(kind = WebRecordKind.TRADE,
            cursor = first.nextCursor, pageSize = 100))
        assertEquals(100, second.items.size)
        assertEquals(99_900L, second.items.first().id)
        assertTrue(first.items.map { it.id }.toSet().intersect(second.items.map { it.id }.toSet()).isEmpty())
    }

    @Test fun record_filters_escape_search_wildcards_and_return_trade_details() = runBlocking {
        seedTrades(3, symbol = "A_%<script>")
        database.openHelper.writableDatabase.execSQL("""INSERT INTO cash_entries(
            id,original_operation_id,savings_account_id,currency_code,cash_account_id,source_kind,source_id,
            delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms)
            VALUES(1,'web-load-3',1,'USD',1,'TRADE',3,-1272,3,'linked',1,0,3,3)""")
        val literal = repository.records(WebRecordFilter(kind = WebRecordKind.TRADE,
            accountId = 1, childId = 1, query = "A_%<script>", pageSize = 50))
        assertEquals(3, literal.items.size)
        assertEquals("1", literal.items.first().quantity)
        assertEquals("12.3456789", literal.items.first().unitPrice)
        assertEquals("0.37", literal.items.first().fee)
        val linkedCash = repository.records(WebRecordFilter(kind = WebRecordKind.CASH, pageSize = 50)).items.single()
        assertEquals("TRADE", linkedCash.action)
        assertEquals(3L, linkedCash.sourceId)
        assertEquals(1L, linkedCash.sourceParentId)
        val wildcard = repository.records(WebRecordFilter(query = "%_", pageSize = 50))
        assertTrue(wildcard.items.isEmpty())
    }

    private fun seedTrades(count: Int, symbol: String = "AAPL") {
        database.runInTransaction {
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms,revision) VALUES(1,'Long history','',1,1,1)")
            db.execSQL("""INSERT INTO cash_accounts(id,savings_account_id,currency_code,balance_minor,revision,
                updated_at_ms,name,note,currency_locked,created_at_ms)
                VALUES(1,1,'USD',0,1,1,'USD cash','',1,1)""")
            db.execSQL("INSERT INTO asset_types(id,name,normalized_name,created_at_ms,updated_at_ms) VALUES(1,'Stock','stock',1,1)")
            db.execSQL("""INSERT INTO instruments(id,asset_type_id,name,symbol,currency_code,current_price_e5,currency_locked,revision,symbol_locked,price_updated_at_ms,created_at_ms,updated_at_ms)
                VALUES(1,1,'Apple',?,'USD',12345000,1,1,1,1,1,1)""", arrayOf(symbol))
            db.execSQL("""INSERT INTO investments(id,savings_account_id,instrument_id,opening_quantity_e8,holding_quantity_e8,revision,created_at_ms,updated_at_ms,opening_cost_price_e8,opening_at_ms,remaining_cost,realized_profit,chronology_valid,algorithm_version,position_state,last_activity_at_ms)
                VALUES(1,1,1,0,?,1,1,1,NULL,0,'0','0',1,1,'HOLDING',?)""",
                arrayOf(count.toLong() * 100_000_000L, count.toLong()))
            val operation = db.compileStatement("INSERT INTO operations(operation_id,kind,request_fingerprint,result_kind,result_id,created_at_ms) VALUES(?,'TRADE','fixture','INVESTMENT_TRADE',?,?)")
            val trade = db.compileStatement("""INSERT INTO investment_trades(id,investment_id,operation_id,direction,quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,cash_account_id,occurred_at_ms,created_at_ms,revision,is_deleted,updated_at_ms,fee_minor)
                VALUES(?,1,?,'BUY',100000000,1234567890,12346,'USD',0,NULL,?,?,1,0,?,37)""")
            repeat(count) { index ->
                val id = (index + 1).toLong()
                val operationId = "web-load-$id"
                operation.clearBindings(); operation.bindString(1, operationId); operation.bindLong(2, id); operation.bindLong(3, id); operation.executeInsert()
                trade.clearBindings(); trade.bindLong(1, id); trade.bindString(2, operationId); trade.bindLong(3, id); trade.bindLong(4, id); trade.bindLong(5, id); trade.executeInsert()
            }
        }
    }
}
