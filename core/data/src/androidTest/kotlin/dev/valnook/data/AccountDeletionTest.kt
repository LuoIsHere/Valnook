package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.data.portability.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID
import java.io.*

@RunWith(AndroidJUnit4::class)
class AccountDeletionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-08T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private val names = mutableListOf<String>()
    private fun id() = UUID.randomUUID().toString()
    private fun database(): ValnookDatabase {
        val name="deletion-test-${id()}.db"; names+=name
        return Room.databaseBuilder(context,ValnookDatabase::class.java,name).addCallback(ValnookDatabase.seed).build()
    }
    @Before fun prepare() { db=database(); commands=RoomFinancialCommands(db,clock) }
    @After fun close() { db.close(); names.forEach(context::deleteDatabase) }
    private suspend fun account(name:String="Bank") = commands.execute(SaveAccount(id(),null,null,name,"",listOf(
        CashBalanceChange("CNY",1000000,null,name="A"),CashBalanceChange("CNY",500000,null,name="B")))).id
    private suspend fun instrument():Long {
        val type=commands.execute(SaveAssetType(id(),null,"Stock")).id
        return commands.execute(SaveInstrument(id(),null,null,"Stock","AAA",type,"CNY",1000000)).id
    }
    private suspend fun cash(parent:Long,name:String)=RoomOverview(db).snapshot().cash.single{it.account_id==parent&&it.name==name}
    private suspend fun buy(parent:Long,instrument:Long,cash:Long?,time:Long=clock.millis()):Long =
        commands.execute(RecordInvestmentTrade(id(),0,Direction.BUY,200000000,1000000000,time,cash!=null,cash,35,parent,instrument)).id
    private suspend fun deleteCash(parent:Long,cash:Long, engine:RoomFinancialCommands=commands):DeleteBalanceAccount {
        val p=engine.previewAccountDeletion(parent,cash)
        return DeleteBalanceAccount(id(),parent,cash,p.revision,DeletionConfirmation(p.ticket,p.code)).also { engine.execute(it) }
    }
    private suspend fun deleteParent(parent:Long) {
        val p=commands.previewAccountDeletion(parent)
        commands.execute(DeleteAccount(id(),parent,p.revision,DeletionConfirmation(p.ticket,p.code)))
    }
    private fun count(sql:String)=db.openHelper.readableDatabase.query(sql).use{it.count}
    private suspend fun expect(code:ErrorCode,block:suspend()->Unit) {
        try {block();fail("Expected $code")}catch(e:DomainException){assertEquals(code,e.code)}
    }

    private suspend fun verifyBackup() {
        val build=AppBuildInfo("valnook","0.0.12",12,"20261008.1","20261008.1",DATABASE_SCHEMA_VERSION)
        val engine=RoomPortabilityEngine(context,db,clock,build);val backup=ByteArrayOutputStream()
        engine.createBackup(id(),backup){}
        val target=database()
        try {
            val restored=RoomPortabilityEngine(context,target,clock,build)
            val staged=restored.prepareRestore(ByteArrayInputStream(backup.toByteArray()),"deletion.val_backup"){}
            try {restored.commitRestore(staged){}} finally {restored.close(staged)}
            assertEquals(RoomOverview(db).snapshot(),RoomOverview(target).snapshot())
            val workbook=ByteArrayOutputStream();restored.exportWorkbook(id(),AppLanguage.ENGLISH,false,workbook){}
            assertTrue(workbook.size()>100)
        }finally{target.close()}
    }

    @Test fun child_deletion_detaches_trades_and_deposit_legs_and_allows_repeated_edit_and_relink()=runBlocking {
        val parent=account();val a=cash(parent,"A");val b=cash(parent,"B");val instrument=instrument()
        val trade=buy(parent,instrument,a.id)
        val deposit=commands.execute(OpenTermDeposit(id(),parent,"CNY",10000,300000000,
            LocalDate.of(2026,1,1).toEpochDay(),LocalDate.of(2026,2,1).toEpochDay(),true,a.id)).id
        commands.execute(CloseTermDeposit(id(),deposit,true,b.id))
        val before=RoomOverview(db).snapshot();val held=before.positions.single();val bBalance=cash(parent,"B").balance_minor
        val deletion=deleteCash(parent,a.id)
        assertEquals(OperationResult("BALANCE_ACCOUNT",a.id),commands.execute(deletion))
        assertNull(db.cash().cashAccount(a.id));assertEquals(0,count("SELECT id FROM cash_entries WHERE cash_account_id=${a.id}"))
        var t=db.trades().trade(trade)!!;assertFalse(t.cash_linked);assertNull(t.cash_account_id)
        var d=db.deposits().deposit(deposit)!!;assertFalse(d.open_cash_linked);assertEquals(b.id,d.close_cash_account_id)
        val after=RoomOverview(db).snapshot().positions.single()
        assertEquals(held.holding_quantity_e8,after.holding_quantity_e8);assertEquals(held.remainingCost,after.remainingCost)
        assertEquals(bBalance,cash(parent,"B").balance_minor)
        verifyBackup()
        repeat(2) {
            t=db.trades().trade(trade)!!
            commands.execute(EditInvestmentTrade(id(),trade,t.revision,Direction.BUY,t.quantity_e8,t.execution_price_e8,t.occurred_at_ms,false,null,t.fee_minor))
        }
        assertEquals(bBalance,cash(parent,"B").balance_minor)
        t=db.trades().trade(trade)!!
        commands.execute(EditInvestmentTrade(id(),trade,t.revision,Direction.BUY,t.quantity_e8,t.execution_price_e8,t.occurred_at_ms,true,b.id,t.fee_minor))
        assertEquals(bBalance-2035,cash(parent,"B").balance_minor)
        d=db.deposits().deposit(deposit)!!
        commands.execute(EditTermDeposit(id(),deposit,d.revision,d.principal_minor,d.annual_rate_percent_e8,d.start_epoch_day,d.end_epoch_day,false,true,null,b.id))
        assertEquals(bBalance-2035,cash(parent,"B").balance_minor)
        d=db.deposits().deposit(deposit)!!
        commands.execute(EditTermDeposit(id(),deposit,d.revision,d.principal_minor,d.annual_rate_percent_e8,d.start_epoch_day,d.end_epoch_day,true,true,b.id,b.id))
        assertEquals(bBalance-12035,cash(parent,"B").balance_minor)
        t=db.trades().trade(trade)!!;commands.execute(DeleteInvestmentTrade(id(),trade,t.revision))
        assertEquals(bBalance-10000,cash(parent,"B").balance_minor)
        assertEquals(0,count("SELECT * FROM pragma_foreign_key_check"))
    }

    @Test fun shared_credit_limits_copy_full_limit_including_zero_without_transferring_debt()=runBlocking {
        for(limit in listOf(0L,200000L)) {
            val parent=commands.execute(SaveAccount(id(),null,null,"Credit $limit","",listOf(CashBalanceChange("CNY",-1000,null,name="Root",
                type=BalanceAccountType.CREDIT,credit=CreditAccountInput(limit,12,CreditDueRule.AfterStatementDays(20),null))))).id
            val root=RoomOverview(db).snapshot().cash.single{it.account_id==parent}
            commands.execute(SaveAccount(id(),parent,db.accounts().account(parent)!!.revision,"Credit $limit","",listOf("B","C").map{
                CashBalanceChange("CNY",-500,null,name=it,type=BalanceAccountType.CREDIT,
                    credit=CreditAccountInput(null,7,CreditDueRule.FixedDayOfMonth(20),root.id))}))
            val p=commands.previewAccountDeletion(parent,root.id);assertEquals(2,p.transfers.size)
            deleteCash(parent,root.id)
            RoomOverview(db).snapshot().cash.filter{it.account_id==parent}.forEach {
                assertEquals(-500L,it.balance_minor);assertNull(it.creditProfile!!.limitSourceAccountId)
                assertEquals(limit,it.creditProfile!!.creditLimitMinor);assertEquals(7,it.creditProfile!!.statementDay)
            }
        }
        assertEquals(0,count("SELECT * FROM pragma_foreign_key_check"))
    }

    @Test fun main_deletion_preserves_global_instrument_other_account_and_backup_roundtrip()=runBlocking {
        val parent=account("Delete bank");val other=account("Keep broker");val instrument=instrument()
        buy(parent,instrument,cash(parent,"A").id);val otherTrade=buy(other,instrument,cash(other,"A").id)
        commands.execute(OpenTermDeposit(id(),parent,"CNY",10000,300000000,20000,21000,true,cash(parent,"B").id))
        val otherBefore=RoomOverview(db).snapshot().cash.filter{it.account_id==other}
        deleteParent(parent)
        assertNull(db.accounts().account(parent));assertNotNull(db.instruments().instrument(instrument))
        assertNotNull(db.trades().trade(otherTrade));assertEquals(otherBefore,RoomOverview(db).snapshot().cash)
        assertEquals(0,count("SELECT id FROM term_deposits WHERE savings_account_id=$parent"))
        assertEquals(0,count("SELECT id FROM cash_entries WHERE savings_account_id=$parent"))
        assertEquals(0,count("SELECT * FROM audit_event_accounts WHERE account_id=$parent"))
        assertEquals(0,count("SELECT * FROM pragma_foreign_key_check"))
        verifyBackup()
    }

    @Test fun confirmation_scope_staleness_cancellation_and_failure_are_safe()=runBlocking {
        val parent=account();val a=cash(parent,"A");val b=cash(parent,"B")
        val p=commands.previewAccountDeletion(parent,a.id)
        val request=DeleteBalanceAccount(id(),parent,a.id,p.revision,DeletionConfirmation(p.ticket,p.code))
        expect(ErrorCode.FORMAT){commands.execute(request.copy(confirmation=null))}
        expect(ErrorCode.FORMAT){commands.execute(request.copy(confirmation=DeletionConfirmation(p.ticket,"bad")))}
        expect(ErrorCode.STALE_RECORD){commands.execute(request.copy(balanceAccountId=b.id))}
        commands.cancelAccountDeletion(p.ticket)
        expect(ErrorCode.STALE_RECORD){commands.execute(request)}
        val stale=commands.previewAccountDeletion(parent,a.id)
        commands.execute(SetCashBalance(id(),parent,"CNY",500001,b.revision,b.id))
        expect(ErrorCode.STALE_RECORD){commands.execute(request.copy(confirmation=DeletionConfirmation(stale.ticket,stale.code)))}
        val faulty=RoomFinancialCommands(db,clock){if(it==TransactionPoint.BEFORE_RECEIPT)error("synthetic rollback")}
        val fp=faulty.previewAccountDeletion(parent,a.id)
        val failed=request.copy(operation_id=id(),confirmation=DeletionConfirmation(fp.ticket,fp.code))
        try{faulty.execute(failed);fail("rollback expected")}catch(_:IllegalStateException){}
        assertNotNull(db.cash().cashAccount(a.id));assertNull(faulty.operationResult(failed.operation_id))
        assertEquals(0,count("SELECT * FROM pragma_foreign_key_check"))
    }

    @Test fun expired_confirmation_is_rejected_and_zero_balance_account_can_be_deleted()=runBlocking {
        val parent=commands.execute(SaveAccount(id(),null,null,"Empty","",listOf(CashBalanceChange("CNY",0,null,name="Zero")))).id
        val cash=cash(parent,"Zero")
        var instant=clock.instant()
        val moving=object:Clock(){override fun getZone()=clock.zone;override fun withZone(zone:ZoneId)=this;override fun instant()=instant}
        val engine=RoomFinancialCommands(db,moving)
        val p=engine.previewAccountDeletion(parent,cash.id)
        instant=instant.plusSeconds(301)
        expect(ErrorCode.STALE_RECORD){engine.execute(DeleteBalanceAccount(id(),parent,cash.id,p.revision,DeletionConfirmation(p.ticket,p.code)))}
        assertNotNull(db.cash().cashAccount(cash.id))
        deleteCash(parent,cash.id);deleteParent(parent)
        assertTrue(RoomOverview(db).snapshot().accounts.isEmpty())
    }

    @Test fun settled_deposits_page_by_settlement_month_and_open_list_stays_complete()=runBlocking {
        val parent=account();val a=cash(parent,"A")
        suspend fun open()=commands.execute(OpenTermDeposit(id(),parent,"CNY",10000,300000000,
            LocalDate.of(2025,1,1).toEpochDay(),LocalDate.of(2025,2,1).toEpochDay(),true,a.id)).id
        val september=open();val october=open();val stillOpen=open()
        val earlier=RoomFinancialCommands(db,Clock.fixed(Instant.parse("2026-09-30T15:59:59Z"),clock.zone))
        earlier.execute(CloseTermDeposit(id(),september,true,a.id));commands.execute(CloseTermDeposit(id(),october,false))
        val repo=RoomDeposits(db.deposits())
        assertEquals(listOf(september),repo.monthPage(parent,LedgerMonth(YearMonth.of(2026,9),clock.zone),null,50).map{it.id})
        assertEquals(listOf(october),repo.monthPage(parent,LedgerMonth(YearMonth.of(2026,10),clock.zone),null,50).map{it.id})
        assertEquals(listOf(stillOpen),repo.page(parent,false,null,50).map{it.id})
    }

    @Test fun month_pages_use_business_dates_stable_ties_and_exclusive_end()=runBlocking {
        val parent=account();val a=cash(parent,"A");val instrument=instrument()
        val september=LedgerMonth(YearMonth.of(2026,9),clock.zone)
        val august=buy(parent,instrument,a.id,september.startMs-1)
        repeat(55){buy(parent,instrument,a.id,september.startMs)}
        val october=buy(parent,instrument,a.id,september.endMs)
        val position=db.trades().trade(october)!!.investment_id
        val trades=RoomInvestments(db)
        val first=trades.tradeMonthPage(position,september,null,50)
        val last=first.last();val second=trades.tradeMonthPage(position,september,TradeCursor(last.occurred_at_ms,last.id),50)
        assertEquals(50,first.size);assertEquals(5,second.size);assertEquals(55,(first+second).map{it.id}.toSet().size)
        assertFalse((first+second).any{it.id==august||it.id==october})
        val cashRepo=RoomCash(db.cash());val cashPage=cashRepo.cashAccountMonthPage(a.id,september,null,100)
        assertEquals(55,cashPage.size)
        val moved=first.first();commands.execute(EditInvestmentTrade(id(),moved.id,moved.revision,Direction.BUY,moved.quantity_e8,moved.execution_price_e8,
            september.endMs,true,a.id,moved.fee_minor))
        assertEquals(54,cashRepo.cashAccountMonthPage(a.id,september,null,100).size)
        assertEquals(0,trades.tradeMonthPage(position,LedgerMonth(YearMonth.of(2025,12),clock.zone),null,100).size)
    }

    @Test fun deletion_clears_all_affected_historical_cache_and_baselines()=runBlocking {
        val parent=account();val a=cash(parent,"A")
        RoomSettings(db,clock).applyChange(SaveFinancialSettings(0,Currency.of("CNY"),emptyList()))
        val start=LocalDate.of(2026,10,1);val ms=start.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=?",arrayOf(ms))
        db.openHelper.writableDatabase.execSQL("UPDATE cash_entries SET occurred_at_ms=?",arrayOf(ms))
        val stats=RoomStatistics(db,clock);val query=StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,StatisticsPeriod(StatisticsGranularity.DAILY,2026,10))
        stats.loadSeries(query)
        deleteCash(parent,a.id)
        assertEquals(0,count("SELECT * FROM statistics_cache"))
        val points=stats.loadSeries(query).points.filter{!it.future}
        assertTrue(points.isNotEmpty());points.forEach{assertEquals(0,"5000".toBigDecimal().compareTo(it.value!!))}
        deleteParent(parent)
        assertEquals(0,count("SELECT * FROM statistics_baseline_items WHERE account_id=$parent"))
        stats.loadSeries(query).points.filter{!it.future}.forEach{assertEquals(0,it.value!!.signum())}
    }
}
