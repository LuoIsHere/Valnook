package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules as R
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.time.*
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PortfolioDatabaseTest {
    private lateinit var db:ValnookDatabase
    private lateinit var repo:RoomInvestments
    private lateinit var commands:RoomFinancialCommands
    private val clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC)
    private var account=0L
    private var type=0L
    private fun id()=UUID.randomUUID().toString()
    private fun e(value:String)=R.parse_e8(value)
    @Before fun prepare() {runBlocking {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        repo=RoomInvestments(db);commands=RoomFinancialCommands(db,clock)
        account=commands.testAccount("合成账户");type=commands.testType("基金")
    }}
    @After fun close(){db.close()}
    private suspend fun create(name:String)=commands.testInvestment(account,name,name,type,"USD",0,e("180"),null)
    private suspend fun trade(asset:Long,direction:Direction,quantity:String,price:String,time:Long)=
        commands.execute(RecordInvestmentTrade(id(),asset,direction,e(quantity),e(price),time,false)).id
    private suspend fun ids(section:InvestmentSection)=repo.observe_investments(account,50,section).first().map{it.id}

    @Test fun archive_reentry_date_corrections_and_deletions_refresh_portfolio() {runBlocking {
        val first=create("QQQ");val second=create("SPY")
        assertEquals(listOf(second,first),ids(InvestmentSection.PENDING));assertTrue(ids(InvestmentSection.HOLDING).isEmpty())
        val buy=trade(first,Direction.BUY,"10","100",100)
        trade(second,Direction.BUY,"1","100",200)
        assertEquals(listOf(second,first),ids(InvestmentSection.HOLDING))
        val sell=trade(first,Direction.SELL,"10","120",300)
        assertEquals(listOf(second),ids(InvestmentSection.HOLDING));assertEquals(listOf(first),ids(InvestmentSection.CLOSED))
        assertEquals("200.00",repo.observe_profit(first).first()!!.realized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString())
        val again=trade(first,Direction.BUY,"1","200",400)
        assertEquals(listOf(first,second),ids(InvestmentSection.HOLDING));assertTrue(ids(InvestmentSection.CLOSED).isEmpty())
        commands.execute(DeleteInvestmentTrade(id(),again,1))
        assertEquals(listOf(first),ids(InvestmentSection.CLOSED))
        commands.execute(DeleteInvestmentTrade(id(),sell,1))
        assertEquals(listOf(second,first),ids(InvestmentSection.HOLDING))
        commands.execute(EditInvestmentTrade(id(),buy,1,Direction.BUY,e("10"),e("100"),500,false))
        assertEquals(listOf(first,second),ids(InvestmentSection.HOLDING))
        commands.execute(DeleteInvestmentTrade(id(),buy,2))
        assertEquals(listOf(first),ids(InvestmentSection.PENDING));assertTrue(ids(InvestmentSection.CLOSED).isEmpty())
    }}
    @Test fun settled_deposits_leave_main_list_and_opening_date_drives_order() {runBlocking {
        val deposits=RoomDeposits(db.ledger())
        fun day(value:String)=LocalDate.parse(value).toEpochDay()
        suspend fun open(start:String)=commands.execute(OpenTermDeposit(id(),account,"USD",10000,e("3"),day(start),day("2026-04-01"),false)).id
        val first=open("2026-01-01");val second=open("2026-02-01")
        assertEquals(listOf(second,first),deposits.observe_deposits(account,50).first().map{it.id})
        commands.execute(CloseTermDeposit(id(),second,false))
        assertEquals(listOf(first),deposits.observe_deposits(account,50).first().map{it.id})
        assertEquals(listOf(second),deposits.observe_deposits(account,50,true).first().map{it.id})
    }}
    @Test fun empty_position_and_first_buy_are_separate_and_buy_cost_is_editable() {runBlocking {
        val instrument=commands.testInstrument("QQQ","",type,"USD",e("180"))
        val creation=CreateInvestmentPosition(id(),account,instrument)
        val asset=commands.execute(creation).id
        assertEquals(commands.operationResult(creation.operation_id), commands.execute(creation))
        assertEquals(0L, db.ledger().investment(asset)!!.holding_quantity_e8)
        assertTrue(repo.trade_page(asset,null).isEmpty())
        val buy=trade(asset,Direction.BUY,"10","100",Long.MIN_VALUE)
        trade(asset,Direction.SELL,"2","120",100)
        val cost=EditInvestmentTrade(id(),buy,1,Direction.BUY,e("10"),e("90"),Long.MIN_VALUE,false)
        assertEquals(commands.execute(cost),commands.execute(cost))
        val summary=repo.observe_profit(asset).first()!!
        assertEquals("60.00",summary.realized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString());assertEquals("720.00",summary.unrealized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString())
        commands.testUpdatePrice(db,asset,e("200"))
        assertEquals("60.00",repo.observe_profit(asset).first()!!.realized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString())
        assertNull(db.ledger().cash_one(account,"USD"))
        try{commands.execute(cost.copy(operation_id=id()));fail()}catch(error:DomainException){assertEquals(ErrorCode.STALE_RECORD,error.code)}
        assertEquals(e("90"),db.ledger().trade(buy)!!.execution_price_e8)
    }}
    @Test fun profit_reads_entire_history_and_excludes_deleted_trades() {runBlocking {
        val asset=create("QQQ")
        repeat(60){trade(asset,Direction.BUY,"1","100",it.toLong())}
        val sell=trade(asset,Direction.SELL,"10","120",100)
        assertEquals(50,repo.trade_page(asset,null).size)
        assertEquals("200.00",repo.observe_profit(asset).first()!!.realized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString())
        commands.execute(DeleteInvestmentTrade(id(),sell,1))
        assertEquals("0.00",repo.observe_profit(asset).first()!!.realized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString())
        assertEquals("4800.00",repo.observe_profit(asset).first()!!.unrealized!!.setScale(2,java.math.RoundingMode.HALF_UP).toPlainString())
    }}
    @Test fun archive_metadata_rolls_back_with_failed_financial_transaction() {runBlocking {
        val asset=create("QQQ");trade(asset,Direction.BUY,"1","100",100)
        val failing=RoomFinancialCommands(db,clock){if(it==TransactionPoint.AFTER_BUSINESS)throw java.io.IOException()}
        try{failing.execute(RecordInvestmentTrade(id(),asset,Direction.SELL,e("1"),e("120"),200,false));fail()}catch(_:java.io.IOException){}
        assertEquals(listOf(asset),ids(InvestmentSection.HOLDING));assertTrue(ids(InvestmentSection.CLOSED).isEmpty())
        assertEquals(100L,db.ledger().investment(asset)!!.last_activity_at_ms)
    }}
    @Test fun section_filter_is_applied_before_limit() {runBlocking {
        val holding=create("QQQ");trade(holding,Direction.BUY,"1","100",100)
        val closed=create("SPY");trade(closed,Direction.BUY,"1","100",200)
        trade(closed,Direction.SELL,"1","120",300)
        assertEquals(listOf(holding),repo.observe_investments(account,1,InvestmentSection.HOLDING).first().map{it.id})
        assertEquals(listOf(closed),repo.observe_investments(account,1,InvestmentSection.CLOSED).first().map{it.id})
    }}
}
