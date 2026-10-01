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
class RecordDetailReadTest {
    private lateinit var db:ValnookDatabase
    private lateinit var commands:RoomFinancialCommands
    private val clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC)
    private fun id()=UUID.randomUUID().toString()
    private fun day(value:String)=LocalDate.parse(value).toEpochDay()
    @Before fun prepare() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        commands=RoomFinancialCommands(db,clock)
    }
    @After fun close(){db.close()}

    @Test fun trade_detail_is_account_scoped_live_and_independent_of_history_page()=runBlocking {
        val accounts=RoomAccounts(db,clock)
        val account=accounts.save_account(null,"A","");val other=accounts.save_account(null,"B","")
        val repo=RoomInvestments(db,clock);val type=repo.save_type(null,"基金")
        val asset=commands.execute(CreateInvestment(id(),account,"QQQ","",type,"USD",0,R.parse_e8("10"))).id
        val trade=commands.execute(RecordInvestmentTrade(id(),asset,Direction.BUY,R.parse_e8("1"),R.parse_e8("10"),clock.millis(),false)).id
        repeat(60){commands.execute(RecordInvestmentTrade(id(),asset,Direction.BUY,R.parse_e8("1"),R.parse_e8("10"),clock.millis(),false))}
        assertFalse(repo.trade_page(asset,null,50).any{it.id==trade})
        assertEquals(trade,repo.observe_trade(account,trade).first()!!.id)
        assertNull(repo.observe_trade(other,trade).first())
        assertNull(repo.observe_trade(account,Long.MAX_VALUE).first())
        val changed=async(start=CoroutineStart.UNDISPATCHED) {
            withTimeout(10000){repo.observe_trade(account,trade).first{it?.revision==2L}}
        }
        commands.execute(EditInvestmentTrade(id(),trade,1,Direction.BUY,R.parse_e8("2"),R.parse_e8("15"),clock.millis()-86400000,false))
        assertEquals(3000L,changed.await()!!.amount_minor)
        commands.execute(DeleteInvestmentTrade(id(),trade,2))
        assertNull(repo.observe_trade(account,trade).first())
    }

    @Test fun deposit_detail_survives_edit_settlement_and_archive_change()=runBlocking {
        val accounts=RoomAccounts(db,clock)
        val account=accounts.save_account(null,"A","");val other=accounts.save_account(null,"B","")
        val repo=RoomDeposits(db.ledger())
        val deposit=commands.execute(OpenTermDeposit(id(),account,"USD",1000000,R.parse_e8("3"),day("2026-01-01"),day("2026-04-01"),false)).id
        repeat(60){commands.execute(OpenTermDeposit(id(),account,"USD",1000000,R.parse_e8("3"),day("2026-04-01"),day("2026-07-01"),false))}
        assertFalse(repo.observe_deposits(account,50).first().any{it.id==deposit})
        assertEquals(deposit,repo.observe_deposit(account,deposit).first()!!.id)
        assertNull(repo.observe_deposit(other,deposit).first())
        assertNull(repo.observe_deposit(account,Long.MAX_VALUE).first())
        val changed=async(start=CoroutineStart.UNDISPATCHED) {
            withTimeout(10000){repo.observe_deposit(account,deposit).first{it?.revision==2L}}
        }
        commands.execute(EditTermDeposit(id(),deposit,1,2000000,R.parse_e8("4"),day("2026-01-01"),day("2026-04-01"),false,null))
        assertEquals(2000000L,changed.await()!!.principal_minor)
        commands.execute(CloseTermDeposit(id(),deposit,false))
        assertTrue(repo.observe_deposit(account,deposit).first()!!.closed)
        assertEquals(deposit,repo.observe_deposits(account,50,true).first().single().id)
        commands.execute(EditTermDeposit(id(),deposit,3,3000000,R.parse_e8("4"),day("2026-01-01"),day("2026-04-01"),false,false))
        val edited=repo.observe_deposit(account,deposit).first()!!
        assertTrue(edited.closed);assertEquals(3000000L,edited.principal_minor)
    }
}
