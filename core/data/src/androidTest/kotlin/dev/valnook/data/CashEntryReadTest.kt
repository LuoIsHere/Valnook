package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.Direction
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CashEntryReadTest {
    private lateinit var db:ValnookDatabase
    private lateinit var cash:RoomCash
    private lateinit var commands:RoomFinancialCommands
    private val clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC)
    private fun id()=UUID.randomUUID().toString()
    @Before fun prepare() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        cash=RoomCash(db.ledger());commands=RoomFinancialCommands(db,clock)
    }
    @After fun close(){db.close()}

    @Test fun detail_is_account_scoped_live_and_independent_of_list_page()=runBlocking {
        val account=commands.testAccount("A");val other=commands.testAccount("B")
        commands.execute(SetCashBalance(id(),account,"USD",10000,null))
        val entry=cash.observe_entries(account,"USD",50).first().single()
        repeat(60){index->commands.execute(SetCashBalance(id(),account,"USD",10001L+index,1L+index))}
        assertFalse(cash.observe_entries(account,"USD",50).first().any{it.id==entry.id})
        assertEquals(entry,cash.observe_entry(account,entry.id).first())
        assertNull(cash.observe_entry(other,entry.id).first())
        assertNull(cash.observe_entry(account,Long.MAX_VALUE).first())
        val update=async(start=CoroutineStart.UNDISPATCHED) {
            withTimeout(10000){cash.observe_entry(account,entry.id).first{it?.revision==2L}}
        }
        commands.execute(EditCashEntry(id(),entry.id,1,9000,clock.millis()-86400000,"updated"))
        assertEquals("updated",update.await()!!.note)
        assertEquals(9000L,cash.observe_entry(account,entry.id).first()!!.delta_minor)
    }

    @Test fun linked_detail_follows_source_and_disappears_after_unlink_or_delete()=runBlocking {
        val account=commands.testAccount("A")
        commands.execute(SetCashBalance(id(),account,"USD",10000,null))
        val type=commands.testType("基金")
        val asset=commands.testInvestment(account,"QQQ","",type,"USD",0,R.parse_e8("10"),null)
        val trade=commands.execute(RecordInvestmentTrade(id(),asset,Direction.BUY,R.parse_e8("1"),R.parse_e8("10"),clock.millis(),true)).id
        val entry=db.ledger().source_entry("TRADE",trade)!!.id
        assertEquals(asset,cash.observe_entry(account,entry).first()!!.investment_id)
        commands.execute(EditInvestmentTrade(id(),trade,1,Direction.BUY,R.parse_e8("2"),R.parse_e8("10"),clock.millis(),true))
        assertEquals(-2000L,cash.observe_entry(account,entry).first()!!.delta_minor)
        commands.execute(EditInvestmentTrade(id(),trade,2,Direction.BUY,R.parse_e8("2"),R.parse_e8("10"),clock.millis(),false))
        assertNull(cash.observe_entry(account,entry).first())
        commands.execute(EditInvestmentTrade(id(),trade,3,Direction.BUY,R.parse_e8("2"),R.parse_e8("10"),clock.millis(),true))
        assertNotNull(cash.observe_entry(account,entry).first())
        commands.execute(DeleteInvestmentTrade(id(),trade,4))
        assertNull(cash.observe_entry(account,entry).first())
    }
}
