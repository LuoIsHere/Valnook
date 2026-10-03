package dev.valnook.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.Direction
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ValnookDatabase::class.java,emptyList(),FrameworkSQLiteOpenHelperFactory())
    @Test fun v3_shared_catalog_cost_cache_and_irreversible_locks_preserve_history(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-v3-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 3).apply {
                execSQL("INSERT INTO currencies VALUES ('USD',2)")
                execSQL("INSERT INTO savings_accounts VALUES (1,'账户一','备注',0,0)")
                execSQL("INSERT INTO savings_accounts VALUES (2,'账户二','',0,0)")
                execSQL("INSERT INTO asset_types VALUES (1,'ETF','etf',0,0)")
                // Equal codes in the old schema are not proof of a shared identity.
                execSQL("INSERT INTO investments VALUES (1,1,1,'QQQ','QQQ','USD',1000000000,600000000,18012345678,0,3,0,30,10000000000,'HOLDING',30)")
                execSQL("INSERT INTO investments VALUES (2,2,1,'QQQ','QQQ','USD',0,0,18000000000,0,3,0,30,NULL,'PENDING',0)")
                execSQL("INSERT INTO operations VALUES ('old-sell','SELL','preserve-me','INVESTMENT_TRADE',1,30)")
                execSQL("INSERT INTO operations VALUES ('old-deleted','BUY','preserve-deleted','INVESTMENT_TRADE',2,30)")
                execSQL("INSERT INTO investment_trades VALUES (1,1,'old-sell','SELL',400000000,13000000000,52000,'USD',0,30,30,2,0,30)")
                execSQL("INSERT INTO investment_trades VALUES (2,2,'old-deleted','BUY',100000000,10000000000,10000,'USD',0,20,20,2,1,30)")
                close()
            }
            helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).apply {
                query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                query("SELECT COUNT(*) FROM instruments WHERE currency_locked=1").use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                }
                query("SELECT remaining_cost,realized_profit,algorithm_version FROM investments WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals("600", it.getString(0))
                    assertEquals("120", it.getString(1)); assertEquals(2, it.getInt(2))
                }
                query("SELECT current_price_e5 FROM instruments WHERE id=1").use {
                    assertTrue(it.moveToFirst()); assertEquals(18012346L, it.getLong(0))
                }
                query("SELECT request_fingerprint FROM operations WHERE operation_id='old-sell'").use {
                    assertTrue(it.moveToFirst()); assertEquals("preserve-me", it.getString(0))
                }
                query("SELECT COUNT(*) FROM investment_trades").use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }
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
    @Test fun v4_to_v5_performs_only_the_authorized_business_reset_and_keeps_currency_catalog() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-v4-reset-${UUID.randomUUID()}.db"
        helper.createDatabase(name,4).apply {
            execSQL("INSERT INTO currencies VALUES ('CNY',2)")
            execSQL("INSERT INTO currencies VALUES ('USD',2)")
            execSQL("INSERT INTO savings_accounts VALUES (1,'旧账户','旧备注',0,0,1)")
            execSQL("INSERT INTO cash_balances VALUES (1,'CNY',91010,4,50)")
            execSQL("INSERT INTO asset_types VALUES (1,'旧类型','旧类型',0,0)")
            execSQL("INSERT INTO instruments VALUES (1,1,'旧投资','OLD','CNY',12000000,1,1,50,0,50)")
            execSQL("INSERT INTO investments VALUES (1,1,1,1000000000,1300000000,3,0,50,10000000000,0,'130','30',1,2,'HOLDING',50)")
            execSQL("INSERT INTO operations VALUES ('old-buy','BUY','fingerprint','INVESTMENT_TRADE',1,50)")
            execSQL("INSERT INTO investment_trades VALUES (1,1,'old-buy','BUY',300000000,10000000000,30000,'CNY',1,50,50,1,0,50)")
            execSQL("INSERT INTO app_settings VALUES (1,'CNY',1)")
            execSQL("INSERT INTO fx_rates VALUES ('USD','CNY','7.2',50)")
            close()
        }
        try {
            helper.runMigrationsAndValidate(name,5,true,MIGRATION_4_5).apply {
                query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
                query("SELECT GROUP_CONCAT(code, ',') FROM currencies ORDER BY code").use {
                    assertTrue(it.moveToFirst());assertEquals("CNY,USD",it.getString(0))
                }
                listOf("savings_accounts","cash_accounts","asset_types","instruments","investments",
                    "investment_trades","term_deposits","cash_entries","cash_movements","operations",
                    "app_settings","fx_rates").forEach { table ->
                    query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst());assertEquals("$table should be reset",0,it.getInt(0))
                    }
                }
                query("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='cash_balances'").use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
                }
                close()
            }
        } finally {context.deleteDatabase(name)}
    }
    @Test fun v5_to_v6_preserves_trades_with_zero_fee_and_updates_cost_algorithm() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-v5-fee-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name,5).apply {
                execSQL("INSERT INTO currencies VALUES ('CNY',2)")
                execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms,revision) VALUES (1,'账户','',0,0,1)")
                execSQL("INSERT INTO asset_types VALUES (1,'A股股票','a股股票',0,0)")
                execSQL("INSERT INTO instruments VALUES (1,1,'贵州茅台','600519.SH','CNY',146820000,1,1,1,0,0,0)")
                execSQL("INSERT INTO investments VALUES (1,1,1,100000000,100000000,1,0,0,10000000000,0,'100','0',1,2,'HOLDING',0)")
                execSQL("INSERT INTO operations VALUES ('buy','ACCOUNT_TRADE','fingerprint','INVESTMENT_TRADE',1,0)")
                execSQL("INSERT INTO investment_trades VALUES (1,1,'buy','BUY',100000000,10000000000,10000,'CNY',0,NULL,1,0,1,0,0)")
                close()
            }
            helper.runMigrationsAndValidate(name,6,true,MIGRATION_5_6).apply {
                query("SELECT fee_minor FROM investment_trades WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals(0L,it.getLong(0))
                }
                query("SELECT algorithm_version FROM investments WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals(3,it.getInt(0))
                }
                query("SELECT remaining_cost,realized_profit,holding_quantity_e8 FROM investments WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals("100",it.getString(0))
                    assertEquals("0",it.getString(1));assertEquals(100000000L,it.getLong(2))
                }
                query("SELECT operation_id,amount_minor,quantity_e8,execution_price_e8 FROM investment_trades WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals("buy",it.getString(0))
                    assertEquals(10000L,it.getLong(1));assertEquals(100000000L,it.getLong(2))
                    assertEquals(10000000000L,it.getLong(3))
                }
                query("SELECT request_fingerprint,result_kind,result_id FROM operations WHERE operation_id='buy'").use {
                    assertTrue(it.moveToFirst());assertEquals("fingerprint",it.getString(0))
                    assertEquals("INVESTMENT_TRADE",it.getString(1));assertEquals(1L,it.getLong(2))
                }
                query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                close()
            }
        } finally {context.deleteDatabase(name)}
    }

    @Test fun opening_an_existing_schema6_database_keeps_business_rows_and_operation_receipts()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="existing-v6-${UUID.randomUUID()}.db"
        val migrations=arrayOf(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6)
        var database=Room.databaseBuilder(context,ValnookDatabase::class.java,name)
            .addCallback(ValnookDatabase.seed).addMigrations(*migrations).build()
        try{
            val commands=RoomFinancialCommands(database,java.time.Clock.systemUTC())
            val account=commands.testAccount("schema6 account")
            val type=commands.testType("ETF")
            val instrument=commands.testInstrument("QQQ","QQQ",type,"USD",18000000000L)
            val operationId=UUID.randomUUID().toString()
            val result=commands.execute(CreateInvestmentPosition(operationId,account,instrument))
            commands.execute(RecordInvestmentTrade(UUID.randomUUID().toString(),result.id,Direction.BUY,
                100000000L,10000000000L,1234L,false))
            val positionBefore=requireNotNull(database.positions().investment(result.id))
            val receiptBefore=requireNotNull(database.operations().operation(operationId))
            database.close()
            database=Room.databaseBuilder(context,ValnookDatabase::class.java,name)
                .addMigrations(*migrations).build()
            assertEquals(positionBefore,database.positions().investment(result.id))
            assertEquals(receiptBefore,database.operations().operation(operationId))
            database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use {
                assertFalse(it.moveToFirst())
            }
        }finally{
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun v6_to_v7_preserves_rows_and_creates_reliable_statistics_baseline() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-v6-statistics-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name,6).apply {
                execSQL("INSERT INTO currencies VALUES ('CNY',2)")
                execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms,revision) VALUES (1,'账户','备注',1,1,1)")
                execSQL("INSERT INTO cash_accounts(savings_account_id,currency_code,balance_minor,revision,updated_at_ms,id,name,note,currency_locked,created_at_ms) VALUES (1,'CNY',12345,1,1,1,'现金','',1,1)")
                execSQL("INSERT INTO asset_types VALUES (1,'股票','股票',1,1)")
                execSQL("INSERT INTO instruments VALUES (1,1,'示例','EX','CNY',12000000,0,1,0,1,1,1)")
                execSQL("INSERT INTO app_settings(id,base_currency,revision,language,gain_loss_scheme) VALUES (1,'CNY',1,'ZH_HANS','GREEN_GAIN')")
                close()
            }
            helper.runMigrationsAndValidate(name,7,true,MIGRATION_6_7).apply {
                query("SELECT name,note FROM savings_accounts WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals("账户",it.getString(0));assertEquals("备注",it.getString(1))
                }
                query("SELECT navigation_order,navigation_visible FROM app_settings WHERE id=1").use {
                    assertTrue(it.moveToFirst())
                    assertEquals("ACCOUNTS,INVESTMENTS,STATISTICS,SETTINGS",it.getString(0))
                    assertEquals("ACCOUNTS,INVESTMENTS,STATISTICS,SETTINGS",it.getString(1))
                }
                query("SELECT COUNT(*) FROM instrument_price_history WHERE instrument_id=1").use {
                    assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))
                }
                query("SELECT amount_long FROM statistics_baseline_items WHERE item_kind='CASH' AND reference_id=1").use {
                    assertTrue(it.moveToFirst());assertEquals(12345L,it.getLong(0))
                }
                query("SELECT rule_version,source_revision FROM statistics_state WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals(1L,it.getLong(1))
                }
                query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun v7_to_v8_turns_opening_position_into_buy_and_uses_deposit_business_date() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val name="migration-v7-business-dates-${UUID.randomUUID()}.db"
        val startDay=java.time.LocalDate.of(2025,1,2).toEpochDay()
        try {
            helper.createDatabase(name,7).apply {
                execSQL("INSERT INTO currencies VALUES ('CNY',2)")
                execSQL("INSERT INTO savings_accounts VALUES (1,'账户','',5000,5000,1)")
                execSQL("INSERT INTO cash_accounts VALUES (1,'CNY',100000,1,5000,1,'现金','',1,5000)")
                execSQL("INSERT INTO asset_types VALUES (1,'股票','股票',5000,5000)")
                execSQL("INSERT INTO instruments VALUES (1,1,'示例','EX','CNY',12000000,1,1,1,5000,5000,5000)")
                execSQL("""INSERT INTO investments VALUES
                    (1,1,1,200000000,200000000,1,5000,6000,10000000000,1234,'200','0',1,3,'HOLDING',1234)""")
                execSQL("INSERT INTO operations VALUES ('deposit-open','OPEN_DEPOSIT','fixture','TERM_DEPOSIT',1,9000)")
                execSQL("""INSERT INTO term_deposits VALUES
                    (1,1,'CNY',50000,300000000,$startDay,${startDay+90},'ACTUAL_DAYS_365',1,
                    'HALF_UP',370,'OPEN',1,NULL,1,NULL,'deposit-open',NULL,NULL,9000,9000,1)""")
                execSQL("""INSERT INTO cash_entries VALUES
                    (1,'deposit-open',1,'CNY',1,'TERM_OPEN',1,-50000,9000,'',1,0,9000,9000)""")
                execSQL("INSERT INTO statistics_state VALUES (1,3,1,0,NULL)")
                execSQL("INSERT INTO statistics_cache VALUES ('TOTAL_ASSETS',$startDay,'1',1,3,1,9000)")
                close()
            }
            helper.runMigrationsAndValidate(name,8,true,MIGRATION_7_8).apply {
                query("""SELECT direction,quantity_e8,execution_price_e8,occurred_at_ms,
                    cash_linked,fee_minor FROM investment_trades WHERE investment_id=1""").use {
                    assertTrue(it.moveToFirst());assertEquals("BUY",it.getString(0))
                    assertEquals(200000000L,it.getLong(1));assertEquals(10000000000L,it.getLong(2))
                    assertEquals(1234L,it.getLong(3));assertEquals(0,it.getInt(4));assertEquals(0L,it.getLong(5))
                    assertFalse(it.moveToNext())
                }
                query("""SELECT opening_quantity_e8,opening_cost_price_e8,opening_at_ms,
                    algorithm_version FROM investments WHERE id=1""").use {
                    assertTrue(it.moveToFirst());assertEquals(0L,it.getLong(0));assertTrue(it.isNull(1))
                    assertEquals(0L,it.getLong(2));assertEquals(4,it.getInt(3))
                }
                val businessTime=java.time.LocalDate.ofEpochDay(startDay)
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                query("SELECT occurred_at_ms FROM cash_entries WHERE source_kind='TERM_OPEN'").use {
                    assertTrue(it.moveToFirst());assertEquals(businessTime,it.getLong(0))
                }
                query("SELECT COUNT(*) FROM statistics_cache").use {
                    assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))
                }
                query("SELECT source_revision,rule_version,earliest_invalidated_epoch_day FROM statistics_state WHERE id=1").use {
                    assertTrue(it.moveToFirst());assertEquals(4L,it.getLong(0));assertEquals(2,it.getInt(1))
                    assertEquals(0L,it.getLong(2))
                }
                query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }
}
