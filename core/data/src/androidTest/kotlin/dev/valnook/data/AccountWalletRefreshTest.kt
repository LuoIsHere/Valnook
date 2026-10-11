package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.data.portability.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.*
import java.time.*
import java.math.BigInteger
import java.util.UUID
import java.util.zip.*
import org.json.*

class AccountWalletRefreshTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),ValnookDatabase::class.java)
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val clock=Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"),ZoneId.of("Asia/Hong_Kong"))
    private lateinit var db:ValnookDatabase
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()}
    @After fun close(){db.close()}
    @Test fun migration_preserves_front_and_private_identity() {
        val name="face-migration-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name,17).apply {
                execSQL("INSERT INTO wallet_cards VALUES (4,'Card',NULL,NULL,3,100,200,2,0)")
                execSQL("INSERT INTO local_wallet_identity VALUES (4,'private-token')");close()
            }
            helper.runMigrationsAndValidate(name,18,true,MIGRATION_17_18).apply {
                query("SELECT name,display_order,revision,face_style FROM wallet_cards WHERE id=4").use {
                    assertTrue(it.moveToFirst());assertEquals("Card",it.getString(0));assertEquals(3,it.getInt(1));assertEquals(2,it.getInt(2));assertTrue(it.isNull(3))
                }
                query("SELECT token FROM local_wallet_identity WHERE card_id=4").use{assertTrue(it.moveToFirst());assertEquals("private-token",it.getString(0))}
                query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())};close()
            }
        } finally {context.deleteDatabase(name)}
    }
    private fun engine()=RoomPortabilityEngine(context,db,clock,AppBuildInfo("valnook","0.0.15",15,"20261011.1","test",18))
    private suspend fun restore(engine:RoomPortabilityEngine,bytes:ByteArray) {
        val staged=engine.prepareRestore(ByteArrayInputStream(bytes),"test.val_backup"){}
        try{engine.commitRestore(staged){}}finally{engine.close(staged)}
    }
    @Test fun styles_round_trip_backup_and_legacy_cards_restore_without_style()=runBlocking {
        val wallet=RoomWallet(db,clock);val style=WalletFaceStyle(WalletFaceBackground.RADIAL,WalletFaceGeometry.BLOCKS)
        val id=wallet.save(null,null,"Geometry",null,null,faceStyle=style)
        val engine=engine();val output=ByteArrayOutputStream();engine.createBackup(UUID.randomUUID().toString(),output){}
        wallet.save(id,1,"Changed",null,null)
        restore(engine,output.toByteArray());assertEquals(style,wallet.observeCards().first().single().faceStyle)
        restore(engine,rewrite(output.toByteArray(),legacy=true))
        assertNull(wallet.observeCards().first().single().faceStyle)
        assertEquals("Geometry",wallet.observeCards().first().single().name)
        val before=wallet.observeCards().first()
        assertTrue(runCatching{restore(engine,rewrite(output.toByteArray(),legacy=false))}.isFailure)
        assertEquals(before,wallet.observeCards().first())
    }
    private fun rewrite(bytes:ByteArray,legacy:Boolean):ByteArray {
        val files=linkedMapOf<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use{zip->while(true){val entry=zip.nextEntry?:break;files[entry.name]=zip.readBytes()}}
        val path="data/wallet_cards.jsonl"
        files[path]=files.getValue(path).toString(Charsets.UTF_8).lineSequence().filter{it.isNotBlank()}.joinToString("\n",postfix="\n") {
            JSONObject(it).apply{if(legacy)remove("face_style")else put("face_style","invalid")}.toString()
        }.toByteArray()
        val manifest=JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        if(legacy) {
            manifest.put("dataSchemaVersion",6)
            val old=manifest.getJSONArray("requiredFeatures")
            manifest.put("requiredFeatures",JSONArray((0 until old.length()).map{old.getString(it)}.filter{it!="wallet-face-style-v1"}))
        }
        val payloads=manifest.getJSONArray("files")
        repeat(payloads.length()){i-> val file=payloads.getJSONObject(i)
            if(file.getString("path")==path) {
                val content=files.getValue(path);file.put("uncompressedBytes",content.size)
                file.put("sha256",java.security.MessageDigest.getInstance("SHA-256").digest(content).joinToString(""){"%02x".format(it)})
            }
        }
        files["manifest.json"]=manifest.toString().toByteArray()
        return ByteArrayOutputStream().also{output->ZipOutputStream(output).use{zip->files.forEach{(name,data)->zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()}}}.toByteArray()
    }
    @Test fun trend_query_and_adjustment_note_follow_atomic_ledger_edits()=runBlocking {
        val commands=RoomFinancialCommands(db,clock);val parent=commands.testAccount("Trends")
        commands.execute(SetCashBalance(testOperationId(),parent,"USD",10000,null))
        val cash=RoomOverview(db).snapshot().cash.single()
        val first=db.cash().cashAccountMonthPage(cash.id,0,Long.MAX_VALUE,null,null,10).single().entry
        val early=Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        db.openHelper.writableDatabase.apply {
            execSQL("UPDATE cash_accounts SET created_at_ms=? WHERE id=?",arrayOf(early,cash.id))
            execSQL("UPDATE cash_entries SET occurred_at_ms=? WHERE id=?",arrayOf(early,first.id))
            execSQL("UPDATE statistics_state SET baseline_at_ms=?",arrayOf(early))
        }
        val operation=testOperationId()
        val command=SetCashBalance(operation,parent,"USD",12500,cash.revision,cash.id,entryNote=" Adjustment ")
        val result=commands.execute(command);assertEquals(result,commands.execute(command))
        val repository=RoomCash(db.cash())
        assertEquals("Adjustment",repository.observeCashEntry(cash.id,result.id).first()!!.note)
        val points=repository.observeTrends(parent,clock.instant(),clock.zone).first().getValue(cash.id)
        assertEquals(LocalDate.of(2026,7,11),points.first().date)
        assertEquals(LocalDate.of(2026,10,11),points.last().date)
        assertEquals(93,points.size)
        assertTrue(points.dropLast(1).all{it.balanceMinor==BigInteger.valueOf(10000)})
        assertEquals(BigInteger.valueOf(12500),points.last().balanceMinor)
        assertTrue(runCatching{commands.execute(command.copy(operation_id=testOperationId(),balance_minor=15000))}.isFailure)
        assertEquals(12500L,repository.observeCashAccount(cash.id).first()!!.balance_minor)
    }
    @Test fun closed_read_model_keeps_account_identity_and_excludes_pending_positions()=runBlocking {
        val commands=RoomFinancialCommands(db,clock)
        val a=commands.testAccount("A");val b=commands.testAccount("B");val type=commands.testType()
        suspend fun closed(parent:Long,name:String):Long {
            val id=commands.testInvestment(parent,name,name,type,"USD",100000000,100000000,100000000)
            commands.execute(RecordInvestmentTrade(testOperationId(),id,Direction.SELL,100000000,100000000,clock.millis(),false))
            return id
        }
        val own=closed(a,"Own");val other=closed(b,"Other")
        val pending=commands.testInvestment(a,"Pending","Pending",type,"USD",0,100000000,null)
        val rows=RoomOverview(db).snapshot().positions
        assertEquals(listOf(own),rows.filter{it.account_id==a&&it.isClosed}.map{it.id})
        assertTrue(rows.single{it.id==other}.isClosed)
        assertFalse(rows.single{it.id==pending}.isClosed)
        assertEquals(2,rows.count{it.isClosed})
    }
}
