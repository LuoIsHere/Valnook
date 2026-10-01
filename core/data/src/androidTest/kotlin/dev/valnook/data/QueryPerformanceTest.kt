package dev.valnook.data

import android.os.Build
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.test.filters.LargeTest
import dev.valnook.data.database.ValnookDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import androidx.sqlite.db.SupportSQLiteDatabase

@LargeTest
class QueryPerformanceTest {
    private fun rows(db:SupportSQLiteDatabase,sql:String):Int = db.query(sql).use{cursor->
        var n=0;while(cursor.moveToNext()) {cursor.getLong(0);n++};n}
    private fun measure(db:SupportSQLiteDatabase,name:String,sql:String,expected:Int):JSONObject {
        val plan=JSONArray()
        db.query("EXPLAIN QUERY PLAN $sql").use{while(it.moveToNext())plan.put(it.getString(3))}
        val first_started=System.nanoTime();val first_count=rows(db,sql)
        val first_ms=(System.nanoTime()-first_started)/1_000_000.0
        assertEquals(expected,first_count)
        val times=(1..30).map{val started=System.nanoTime();assertEquals(expected,rows(db,sql));
            (System.nanoTime()-started)/1_000_000.0}.sorted()
        assertTrue(plan.toString().contains("INDEX")||plan.toString().contains("PRIMARY KEY"))
        assertFalse(plan.toString().contains("USE TEMP B-TREE"))
        return JSONObject().put("name",name).put("sql",sql).put("query_plan",plan).put("returned",first_count)
            .put("repetitions",30).put("first_read_ms",first_ms).put("p50_ms",times[14]).put("p95_ms",times[28])
            .put("cache_state","first read after population; repeated reads warm; OS cold cache not controlled")
    }
    @Test fun indexed_queries_with_deterministic_small_and_large_fixtures() {runBlocking{
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val args=InstrumentationRegistry.getArguments()
        val seed=args.getString("seed")?.toInt() ?: 20260930
        val output=JSONArray()
        for(large in listOf(false,true)){
            val account_count=if(large)args.getString("accounts")?.toInt() ?:20 else 2
            val deposit_count=if(large)args.getString("deposits")?.toInt() ?:5000 else 10
            val investment_count=if(large)args.getString("investments")?.toInt() ?:1000 else 2
            val trade_count=if(large)args.getString("trades")?.toInt() ?:50000 else 200
            require(account_count in 1..100&&investment_count in 1..10000&&trade_count in 100..1000000)
            val db=Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
            try{
                db.withTransaction{
                    val sql=db.openHelper.writableDatabase;val random=Random(seed)
                    for(a in 1..account_count){
                        sql.execSQL("INSERT INTO savings_accounts VALUES (?,?,?,?,?)",arrayOf<Any>(a,"fixture-$a","synthetic",0,0))
                        sql.execSQL("INSERT INTO cash_balances VALUES (?,?,?,?,?)",arrayOf<Any>(a,"CNY",100000000000L,1,0))
                        val fund="fixture-fund-$a"
                        sql.execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?)",arrayOf<Any>(fund,"CASH_SET","fixture","CASH_ENTRY",-a,0))
                        sql.execSQL("INSERT INTO cash_entries VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(-a,fund,a,"CNY","CASH_SET",null,100000000000L,0,"",1,0,0,0))
                    }
                    sql.execSQL("INSERT INTO asset_types VALUES (1,'synthetic','synthetic',0,0)")
                    for(i in 1..investment_count)sql.execSQL("INSERT INTO investments(id,savings_account_id,asset_type_id,name,symbol,currency_code,opening_quantity_e8,holding_quantity_e8,current_price_e8,price_updated_at_ms,revision,created_at_ms,updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        arrayOf<Any>(i,(i-1)%account_count+1,1,"asset-"+i.toString().padStart(5,'0'),"","CNY",0,0,10000000000L,0,1,0,0))
                    for(d in 1..deposit_count){
                        val op="fixture-deposit-$d"
                        sql.execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?)",arrayOf<Any>(op,"TERM_OPEN","fixture","TERM_DEPOSIT",d,0))
                        val start=20454L+random.nextInt(0,200)
                        sql.execSQL("INSERT INTO term_deposits(id,savings_account_id,currency_code,principal_minor,annual_rate_percent_e8,start_epoch_day,end_epoch_day,interest_rule,calculation_version,rounding_mode,expected_interest_minor,status,open_cash_linked,close_cash_linked,open_operation_id,close_operation_id,closed_at_ms,created_at_ms,updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any?>(d,(d-1)%account_count+1,"CNY",1000000,300000000,start,start+365,"ACT_365F_SIMPLE",1,"HALF_UP",30000,"OPEN",0,null,op,null,null,0,0))
                    }
                    val holding=LongArray(investment_count)
                    for(t in 1..trade_count){
                        val op="fixture-trade-$t";val inv=((t-1)/100)%investment_count+1;holding[inv-1]+=100000000
                        sql.execSQL("INSERT INTO operations VALUES (?,?,?,?,?,?)",arrayOf<Any>(op,"BUY","fixture","INVESTMENT_TRADE",t,0))
                        sql.execSQL("INSERT INTO investment_trades(id,investment_id,operation_id,direction,quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,occurred_at_ms,created_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(t,inv,op,"BUY",100000000,10000000000L,10000,"CNY",1,1000,0))
                        val owner=(inv-1)%account_count+1
                        sql.execSQL("INSERT INTO cash_entries VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            arrayOf<Any>(t,op,owner,"CNY","TRADE",t,-10000,1000,"",1,0,0,0))
                        sql.execSQL("UPDATE cash_balances SET balance_minor=balance_minor-10000 WHERE savings_account_id=? AND currency_code='CNY'",arrayOf<Any>(owner))
                    }
                    holding.forEachIndexed{index,value->sql.execSQL("UPDATE investments SET holding_quantity_e8=?,position_state=?,last_activity_at_ms=1000 WHERE id=?",arrayOf<Any>(value,if(value>0)"HOLDING" else "PENDING",index+1))}
                }
                val sql=db.openHelper.writableDatabase
                val inv_on_account=(minOf(investment_count,(trade_count+99)/100)+account_count-1)/account_count
                val dep_on_account=(deposit_count+account_count-1)/account_count
                val stats=JSONArray()
                stats.put(measure(sql,"cash","SELECT balance_minor FROM cash_balances WHERE savings_account_id=1 AND currency_code='CNY'",1))
                stats.put(measure(sql,"cash-history","SELECT e.id,e.delta_minor,t.investment_id FROM cash_entries e LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id WHERE e.savings_account_id=1 AND e.currency_code='CNY' AND e.is_deleted=0 ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT 50",50))
                stats.put(measure(sql,"deposits","SELECT id,principal_minor,expected_interest_minor FROM term_deposits WHERE savings_account_id=1 AND status='OPEN' ORDER BY start_epoch_day DESC,id DESC LIMIT 50",minOf(50,dep_on_account)))
                stats.put(measure(sql,"settled-deposits","SELECT id FROM term_deposits WHERE savings_account_id=1 AND status='CLOSED' ORDER BY start_epoch_day DESC,id DESC LIMIT 50",0))
                stats.put(measure(sql,"matured","SELECT id FROM term_deposits WHERE savings_account_id=1 AND status='OPEN' AND end_epoch_day<=21020 ORDER BY end_epoch_day,id LIMIT 50",minOf(50,dep_on_account)))
                stats.put(measure(sql,"investments","SELECT i.id,i.holding_quantity_e8,i.current_price_e8,t.name FROM investments i JOIN asset_types t ON t.id=i.asset_type_id WHERE i.savings_account_id=1 AND i.position_state='HOLDING' ORDER BY i.last_activity_at_ms DESC,i.id DESC LIMIT 50",minOf(50,inv_on_account)))
                stats.put(measure(sql,"closed-investments","SELECT id FROM investments WHERE savings_account_id=1 AND position_state='CLOSED' ORDER BY last_activity_at_ms DESC,id DESC LIMIT 50",0))
                stats.put(measure(sql,"trades-first","SELECT id,quantity_e8,execution_price_e8,amount_minor FROM investment_trades WHERE investment_id=1 AND is_deleted=0 ORDER BY occurred_at_ms DESC,id DESC LIMIT 50",50))
                stats.put(measure(sql,"trades-next","SELECT id,quantity_e8,execution_price_e8,amount_minor FROM investment_trades WHERE investment_id=1 AND is_deleted=0 AND (occurred_at_ms<1000 OR (occurred_at_ms=1000 AND id<51)) ORDER BY occurred_at_ms DESC,id DESC LIMIT 50",50))
                output.put(JSONObject().put("dataset",if(large)"large" else "small").put("seed",seed).put("accounts",account_count)
                    .put("deposits",deposit_count).put("investments",investment_count).put("trades",trade_count).put("queries",stats))
            }finally{db.close()}
        }
        val report=JSONObject().put("sdk",Build.VERSION.SDK_INT).put("model",Build.MODEL)
            .put("database","isolated in-memory Room / device SQLite").put("datasets",output).toString(2)
        PlatformTestStorageRegistry.getInstance().openOutputFile("performance.json").use{it.write(report.toByteArray(Charsets.UTF_8))}
    }}
}
