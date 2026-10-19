package dev.valnook.data

import android.graphics.Bitmap
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.image.WalletImages
import dev.valnook.data.portability.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WalletDataTest {
    private val clock=Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"),ZoneId.of("Asia/Hong_Kong"))
    private lateinit var db:ValnookDatabase
    private lateinit var wallet:RoomWallet
    private lateinit var commands:RoomFinancialCommands
    @Before fun prepare(){db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build();wallet=RoomWallet(db,clock);commands=RoomFinancialCommands(db,clock)}
    @After fun close(){db.close()}
    private fun image(noisy:Boolean=false,color:Int=0xff556677.toInt(),start:Int=1000):WalletImage {
        // Same output contract as the wallet editor (card aspect, static WebP, size cap) without a feature dependency.
        var width=if(noisy)1600 else start
        while(true) {
            val height=Math.round(width/WalletRules.ASPECT)
            val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            try {
                if(noisy){val random=java.util.Random(42);val pixels=IntArray(width*height){0xff000000.toInt() or random.nextInt(0x1000000)};bitmap.setPixels(pixels,0,width,0,0,width,height)}
                else bitmap.eraseColor(color)
                for(quality in listOf(92,85,78)) {
                    val out=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY,quality,out))
                    val bytes=out.toByteArray()
                    if(bytes.size<=WalletRules.MAX_IMAGE_BYTES)return WalletImage(WalletImageFormat.digest(bytes),bytes,width,height,0xff556677L)
                }
            } finally { bitmap.recycle() }
            width=(width*.8f).toInt()
        }
    }
    @Test fun images_are_reference_safe_and_stale_edits_and_sorts_roll_back()=runBlocking {
        val image=image();assertTrue(WalletImages.valid(image))
        val first=wallet.save(null,null," First ",null,image.key,image)
        val second=wallet.save(null,null,"Second",null,image.key)
        val cards=wallet.observeCards().first();assertEquals("First",cards.first().name)
        wallet.reorder(listOf(first,second),listOf(second,first))
        assertTrue(runCatching{wallet.reorder(listOf(first,second),listOf(second,first))}.isFailure)
        wallet.save(first,1,"Edited",null,image.key)
        assertTrue(runCatching{wallet.save(first,1,"Stale",null,null)}.isFailure)
        wallet.delete(first,2);assertNotNull(wallet.image(image.key));wallet.delete(second,1);assertNotNull(wallet.image(image.key))
        assertTrue(wallet.observeCards().first().isEmpty())
    }
    @Test fun replaced_and_deleted_faces_stay_in_the_library_until_deleted_there()=runBlocking {
        val first=image();val second=image(color=0xff884422.toInt())
        val card=wallet.save(null,null," Daily ",null,first.key,first)
        assertEquals(listOf("Daily"),wallet.observeFaces().first().single().cardNames)
        assertEquals("Daily",wallet.observeFaces().first().single().label)
        assertEquals(clock.millis(),wallet.observeFaces().first().single().addedAtMs)
        wallet.save(card,1,"Renamed",null,second.key,second)
        var faces=wallet.observeFaces().first().associateBy{it.key}
        assertEquals(setOf(first.key,second.key),faces.keys)
        assertFalse(faces.getValue(first.key).inUse);assertEquals(listOf("Renamed"),faces.getValue(second.key).cardNames)
        // A face that comes back keeps its library name.
        wallet.save(card,2,"Back",null,first.key,first)
        assertEquals("Daily",wallet.observeFaces().first().single{it.key==first.key}.label)
        val generation=db.audit().generation()
        assertEquals(ErrorCode.WALLET_FACE_IN_USE,(runCatching{wallet.deleteFace(first.key)}.exceptionOrNull() as DomainException).code)
        assertEquals(generation,db.audit().generation())
        wallet.deleteFace(second.key);assertNull(wallet.image(second.key));assertTrue(db.audit().generation()>generation)
        assertEquals(ErrorCode.NOT_FOUND,(runCatching{wallet.deleteFace(second.key)}.exceptionOrNull() as DomainException).code)
        wallet.delete(card,3)
        faces=wallet.observeFaces().first().associateBy{it.key}
        assertEquals(setOf(first.key),faces.keys);assertFalse(faces.getValue(first.key).inUse)
        wallet.deleteFace(first.key);assertTrue(wallet.observeFaces().first().isEmpty())
    }
    @Test fun uploads_are_named_validated_unique_and_capped_at_fifty()=runBlocking {
        val upload=image()
        assertTrue(runCatching{wallet.addFace("   ",upload)}.isFailure)
        assertTrue(runCatching{wallet.addFace("Bad",upload.copy(key="0".repeat(64)))}.isFailure)
        assertTrue(wallet.observeFaces().first().isEmpty())
        wallet.addFace(" Travel ",upload)
        val face=wallet.observeFaces().first().single();assertEquals("Travel",face.label);assertFalse(face.inUse)
        assertEquals(ErrorCode.WALLET_FACE_DUPLICATE,(runCatching{wallet.addFace("Again",upload)}.exceptionOrNull() as DomainException).code)
        // Applying a library face to a card needs no image bytes.
        val card=wallet.save(null,null,"Card",null,upload.key)
        assertEquals(listOf("Card"),wallet.observeFaces().first().single().cardNames)
        repeat(WalletRules.MAX_FACES-1){wallet.addFace("Face $it",image(color=0xff000000.toInt() or (it*4999+1),start=200))}
        assertEquals(WalletRules.MAX_FACES,wallet.observeFaces().first().size)
        val extra=image(color=0xff123456.toInt(),start=200)
        assertEquals(ErrorCode.WALLET_FACE_LIBRARY_FULL,(runCatching{wallet.addFace("Extra",extra)}.exceptionOrNull() as DomainException).code)
        assertEquals(ErrorCode.WALLET_FACE_LIBRARY_FULL,(runCatching{wallet.save(card,1,"Card",null,extra.key,extra)}.exceptionOrNull() as DomainException).code)
        assertEquals(upload.key,wallet.observeCards().first().single().imageKey)
        // A full library still lets cards switch between the faces it holds.
        val held=wallet.observeFaces().first().first{!it.inUse}.key
        wallet.save(card,1,"Card",null,held)
        assertEquals(WalletRules.MAX_FACES,wallet.observeFaces().first().size)
    }
    @Test fun deleting_bound_account_preserves_cards_without_changing_other_accounts()=runBlocking {
        val account=commands.testAccount("A");commands.execute(SetCashBalance(testOperationId(),account,"USD",100,null))
        val cash=RoomOverview(db).snapshot().cash.single()
        wallet.save(null,null,"One",cash.id,null);wallet.save(null,null,"Two",cash.id,null)
        val preview=commands.previewAccountDeletion(account,cash.id)
        commands.execute(DeleteBalanceAccount(testOperationId(),account,cash.id,cash.revision,DeletionConfirmation(preview.ticket,preview.code)))
        val cards=wallet.observeCards().first();assertEquals(2,cards.size);assertTrue(cards.all{it.boundCashAccountId==null&&it.bindingLost})
    }
    @Test fun backup_restores_images_links_and_order_without_duplicate_balances()=runBlocking {
        val account=commands.testAccount("A");commands.execute(SetCashBalance(testOperationId(),account,"USD",12345,null))
        val cash=RoomOverview(db).snapshot().cash.single();val image=image(noisy=true)
        assertTrue(image.bytes.size>128*1024);assertTrue(image.bytes.size<=512*1024)
        val first=wallet.save(null,null,"Linked",cash.id,image.key,image);val second=wallet.save(null,null,"Collection",null,null)
        wallet.reorder(listOf(first,second),listOf(second,first))
        val unused=image(color=0xff336699.toInt());wallet.addFace("Spare",unused)
        val engine=RoomPortabilityEngine(ApplicationProvider.getApplicationContext(),db,clock,AppBuildInfo("valnook","0.0.13",13,"20261008.2","20261008.2",16))
        val out=ByteArrayOutputStream();engine.createBackup(UUID.randomUUID().toString(),out){}
        wallet.delete(first,1);wallet.deleteFace(unused.key)
        val staged=engine.prepareRestore(ByteArrayInputStream(out.toByteArray()),"wallet.val_backup"){}
        try{engine.commitRestore(staged){}}finally{engine.close(staged)}
        val restored=wallet.observeCards().first();assertEquals(listOf(second,first),restored.map{it.id})
        assertEquals(cash.id,restored.last().boundCashAccountId);assertArrayEquals(image.bytes,wallet.image(image.key)!!.bytes)
        val faces=wallet.observeFaces().first().associateBy{it.key}
        assertEquals("Spare",faces.getValue(unused.key).label);assertFalse(faces.getValue(unused.key).inUse)
        assertEquals("Linked",faces.getValue(image.key).label);assertEquals(listOf("Linked"),faces.getValue(image.key).cardNames)
        assertEquals(12345L,RoomOverview(db).snapshot().cash.single().balance_minor)
        val corrupt=out.toByteArray().copyOf(out.size()/2)
        assertTrue(runCatching{engine.prepareRestore(ByteArrayInputStream(corrupt),"broken.val_backup") {}}.isFailure)
        assertEquals(restored,wallet.observeCards().first())
    }
    @Test fun fifty_cards_and_ten_thousand_same_millisecond_rows_stay_in_the_selected_month()=runBlocking {
        val account=commands.testAccount("A");commands.execute(SetCashBalance(testOperationId(),account,"USD",1,null))
        val cash=RoomOverview(db).snapshot().cash.single();val source=db.cash().cashAccountMonthPage(cash.id,0,Long.MAX_VALUE,null,null,1).single().entry
        repeat(50){wallet.save(null,null,"Card $it",cash.id,null)}
        val month=LedgerMonth(YearMonth.of(2024,2),ZoneId.of("Asia/Hong_Kong"))
        db.withTransaction {
            suspend fun entry(index:Int,time:Long) {
                val operation="wallet-perf-$index"
                db.openHelper.writableDatabase.execSQL("INSERT INTO operations SELECT ?,kind,request_fingerprint,result_kind,result_id,created_at_ms FROM operations WHERE operation_id=?",arrayOf(operation,source.original_operation_id))
                db.ledger().insert_entry(source.copy(id=0,original_operation_id=operation,occurred_at_ms=time,source_id=null))
            }
            repeat(10000){entry(it,month.startMs)}
            entry(10000,month.endMs-1);entry(10001,month.endMs)
        }
        val repository=RoomCash(db.cash());val ids=mutableSetOf<Long>();var cursor:LedgerCursor?=null
        do{val rows=repository.cashAccountMonthPage(cash.id,month,cursor,50)
            assertTrue(rows.all{it.occurred_at_ms>=month.startMs&&it.occurred_at_ms<month.endMs})
            rows.forEach{assertTrue(ids.add(it.id))};cursor=rows.lastOrNull()?.let{LedgerCursor(it.occurred_at_ms,it.id)}
        }while(cursor!=null)
        assertEquals(10001,ids.size);assertEquals(50,wallet.observeCards().first().size)
        assertTrue(repository.cashAccountMonthPage(cash.id,LedgerMonth(YearMonth.of(2024,1),month.zone),null,50).isEmpty())
        assertEquals(month.startMs,repository.observeMonthBounds(cash.id).first().firstMs)
    }
}
