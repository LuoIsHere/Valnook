package dev.valnook.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ValnookDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())
    @Test fun v2_archive_backfill_ignores_deleted_trades_and_keeps_unknown_cost() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-v2-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name,2).apply {
                execSQL("INSERT INTO currencies VALUES ('USD',2)")
                execSQL("INSERT INTO savings_accounts VALUES (1,'旧账户','',0,0)")
                execSQL("INSERT INTO asset_types VALUES (1,'旧类型','旧类型',0,0)")
                execSQL("INSERT INTO investments VALUES (1,1,1,'清仓','QQQ','USD',1000000000,0,12000000000,0,2,10,30)")
                execSQL("INSERT INTO investments VALUES (2,1,1,'草稿','','USD',0,0,0,0,3,15,40)")
                for(id in 1..2) {
                    execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?)",arrayOf<Any>("old-$id","fixture","fingerprint","INVESTMENT_TRADE",id,20))
                    execSQL("INSERT INTO investment_trades VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any>(id,id,"old-$id",if(id==1)"SELL" else "BUY",1000000000,12000000000,120000,"USD",0,20,20,1,if(id==1)0 else 1,20))
                }
                close()
            }
            helper.runMigrationsAndValidate(name,3,true,MIGRATION_2_3).apply {
                query("SELECT position_state,last_activity_at_ms,opening_cost_price_e8 FROM investments ORDER BY id").use {
                    assertTrue(it.moveToNext());assertEquals("CLOSED",it.getString(0));assertEquals(20L,it.getLong(1));assertTrue(it.isNull(2))
                    assertTrue(it.moveToNext());assertEquals("PENDING",it.getString(0));assertEquals(15L,it.getLong(1));assertTrue(it.isNull(2))
                }
                query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
                close()
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun v1_sources_balances_receipts_and_history_survive_and_remain_editable() {runBlocking{
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-${UUID.randomUUID()}.db"
        val op=(1..5).map{UUID.randomUUID().toString()}
        helper.createDatabase(name,1).apply {
            execSQL("INSERT INTO currencies VALUES ('CNY',2)")
            execSQL("INSERT INTO savings_accounts VALUES (1,'旧账户','旧备注',0,0)")
            execSQL("INSERT INTO cash_balances VALUES (1,'CNY',91010,4,50)")
            execSQL("INSERT INTO asset_types VALUES (1,'旧类型','旧类型',0,0)")
            execSQL("INSERT INTO investments VALUES (1,1,1,'旧投资','OLD','CNY',1000000000,1300000000,12000000000,50,3,0,50)")
            op.forEachIndexed{index,id->execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(id,"fixture-$index","original-fingerprint-$index","original-result",index+1,50))}
            execSQL("INSERT INTO investment_trades VALUES (1,1,?,'BUY',100000000,9000000000,9000,'CNY',1,20,50)",arrayOf<Any>(op[1]))
            execSQL("INSERT INTO investment_trades VALUES (2,1,?,'BUY',200000000,100000000,200,'CNY',0,30,50)",arrayOf<Any>(op[4]))
            execSQL("INSERT INTO term_deposits VALUES (1,1,'CNY',10000,10000000,0,365,'ACT_365F_SIMPLE',1,'HALF_UP',10,'CLOSED',1,1,?,?,50,0,50)",arrayOf<Any>(op[2],op[3]))
            val deltas=listOf(100000L,-9000L,-10000L,10010L)
            var balance=0L
            deltas.forEachIndexed{index,delta->
                execSQL("INSERT INTO cash_movements VALUES (?,?,?,?,?,?,?,?,?)",arrayOf<Any>(index+1,op[index],1,"CNY","original",delta,balance,balance+delta,50))
                balance+=delta
            }
            close()
        }
        helper.runMigrationsAndValidate(name,2,true,MIGRATION_1_2).apply {
            query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
            query("SELECT COUNT(*) FROM currencies").use{assertTrue(it.moveToFirst());assertEquals(54,it.getInt(0))}
            close()
        }
        helper.runMigrationsAndValidate(name,3,true,MIGRATION_2_3).close()
        val db=Room.databaseBuilder(context,ValnookDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
        try {
            val dao=db.ledger();val repository=RoomInvestments(db,Clock.systemUTC())
            assertNull(dao.investment(1)!!.opening_cost_price_e8)
            assertEquals("HOLDING",dao.investment(1)!!.position_state)
            assertEquals(30L,dao.investment(1)!!.last_activity_at_ms)
            assertFalse(repository.observe_profit(1).first()!!.cost_complete)
            assertEquals(91010L,dao.cash_one(1,"CNY")!!.balance_minor)
            assertEquals("旧备注",dao.account(1)!!.note)
            assertEquals("original-fingerprint-1",dao.operation(op[1])!!.request_fingerprint)
            assertEquals("original-result",dao.operation(op[1])!!.result_kind)
            assertEquals(listOf(2L,1L),repository.trade_page(1,null).map{it.id})
            assertEquals(1L,repository.get_trade(1)!!.revision)
            val entries=RoomCash(dao).observe_entries(1,"CNY",50).first()
            assertEquals(setOf("CASH_SET","TRADE","TERM_OPEN","TERM_CLOSE"),entries.map{it.source_kind}.toSet())
            assertEquals(20L,entries.single{it.source_kind=="TRADE"}.occurred_at_ms)
            assertEquals(91010L,entries.sumOf{it.delta_minor})
            val commands=RoomFinancialCommands(db,Clock.systemUTC())
            commands.execute(DeleteInvestmentTrade(UUID.randomUUID().toString(),1,1))
            commands.execute(EditCashEntry(UUID.randomUUID().toString(),1,1,90000,10,"迁移后修正"))
            assertEquals(90010L,dao.cash_one(1,"CNY")!!.balance_minor)
            assertEquals(1200000000L,dao.investment(1)!!.holding_quantity_e8)
            assertEquals(12000000000L,dao.investment(1)!!.current_price_e8)
            assertEquals("CLOSED",dao.deposit(1)!!.status)
            assertEquals(90010L,RoomCash(dao).observe_entries(1,"CNY",50).first().sumOf{it.delta_minor})
            commands.execute(EditTermDeposit(UUID.randomUUID().toString(),1,1,20000,10000000,0,365,true,true))
            assertEquals(90020L,dao.cash_one(1,"CNY")!!.balance_minor)
            assertEquals(2L,dao.deposit(1)!!.revision)
            assertEquals(90020L,RoomCash(dao).observe_entries(1,"CNY",50).first().sumOf{it.delta_minor})
        } finally {db.close();context.deleteDatabase(name)}
    }}
}
