package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseTest {
    private lateinit var db: ValnookDatabase
    private lateinit var accounts: RoomAccounts
    private lateinit var investments: RoomInvestments
    private lateinit var commands: RoomFinancialCommands
    private val clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC)
    private fun id()=UUID.randomUUID().toString()
    private fun day(s:String)=LocalDate.parse(s).toEpochDay()
    private fun e(s:String)=R.parse_e8(s)
    @Before fun prepare() {
        db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        accounts=RoomAccounts(db,clock); investments=RoomInvestments(db,clock); commands=RoomFinancialCommands(db,clock)
    }
    @After fun close() { db.close() }
    private suspend fun account(name:String="A")=accounts.save_account(null,name,"synthetic")
    private suspend fun set(a:Long,amount:Long,code:String="CNY") =
        commands.execute(SetCashBalance(id(),a,code,amount,db.ledger().cash_one(a,code)?.revision))
    private suspend fun asset(a:Long,quantity:String="10"):Long {
        val t=investments.save_type(null,"自定义类型")
        return commands.execute(CreateInvestment(id(),a,"合成资产","TEST",t,"CNY",e(quantity),e("100"),e("100"))).id
    }
    private suspend fun expect(code:ErrorCode,block:suspend ()->Unit) {
        try { block(); fail("expected $code") } catch(ex:DomainException) { assertEquals(code,ex.code) }
    }
    private fun count(table:String):Long = db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst(); it.getLong(0) }
    private suspend fun reconcile(a:Long,i:Long) {
        val entries=RoomCash(db.ledger()).observe_entries(a,"CNY",10000).first()
        assertEquals(db.ledger().cash_one(a,"CNY")!!.balance_minor,entries.sumOf{it.delta_minor})
        val asset=db.ledger().investment(i)!!
        val trades=investments.trade_page(i,null,100)
        assertEquals(asset.holding_quantity_e8,asset.opening_quantity_e8+trades.sumOf{if(it.direction==Direction.BUY)it.quantity_e8 else -it.quantity_e8})
        db.openHelper.writableDatabase.query("SELECT SUM(delta_minor) FROM cash_movements WHERE savings_account_id=$a AND currency_code='CNY'").use{
            assertTrue(it.moveToFirst());assertEquals(db.ledger().cash_one(a,"CNY")!!.balance_minor,it.getLong(0))
        }
    }
    @Test fun deposit_source_edits_reconcile_open_and_closed_cash_and_preserve_later_adjustments() {runBlocking{
        val a=account();set(a,2000000);val i=asset(a)
        val d=commands.execute(OpenTermDeposit(id(),a,"CNY",1000000,e("3"),day("2026-01-01"),day("2026-04-01"),true)).id
        set(a,1200000)
        val edit=EditTermDeposit(id(),d,1,800000,e("4"),day("2026-01-01"),day("2026-04-01"),true,null)
        assertEquals(commands.execute(edit),commands.execute(edit))
        assertEquals(1400000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(7890L,db.ledger().deposit(d)!!.expected_interest_minor)
        expect(ErrorCode.STALE_RECORD){commands.execute(edit.copy(operation_id=id()))}
        commands.execute(CloseTermDeposit(id(),d,true))
        val original_open=db.ledger().source_entry("TERM_OPEN",d)!!.id
        val original_close=db.ledger().source_entry("TERM_CLOSE",d)!!.id
        val correction=edit.copy(operation_id=id(),expected_revision=3,principal_minor=700000,annual_rate_percent_e8=e("3"),close_cash_linked=true)
        commands.execute(correction)
        assertEquals(2205178,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals("CLOSED",db.ledger().deposit(d)!!.status)
        assertEquals(-700000L,db.ledger().source_entry("TERM_OPEN",d)!!.delta_minor)
        assertEquals(705178L,db.ledger().source_entry("TERM_CLOSE",d)!!.delta_minor)
        commands.execute(correction.copy(operation_id=id(),expected_revision=4,open_cash_linked=false,close_cash_linked=false))
        assertEquals(2200000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertTrue(db.ledger().cash_entry(original_open)!!.is_deleted);assertTrue(db.ledger().cash_entry(original_close)!!.is_deleted)
        commands.execute(correction.copy(operation_id=id(),expected_revision=5))
        assertEquals(original_open,db.ledger().source_entry("TERM_OPEN",d)!!.id)
        assertEquals(original_close,db.ledger().source_entry("TERM_CLOSE",d)!!.id)
        reconcile(a,i)
    }}
    @Test fun deposit_source_edits_reject_insufficient_cash_future_closed_dates_and_stale_revision() {runBlocking{
        val a=account();set(a,100000);val i=asset(a)
        val d=commands.execute(OpenTermDeposit(id(),a,"CNY",50000,e("3"),day("2026-01-01"),day("2026-04-01"),true)).id
        val edit=EditTermDeposit(id(),d,1,200000,e("3"),day("2026-01-01"),day("2026-04-01"),true,null)
        val ops=count("operations")
        expect(ErrorCode.INSUFFICIENT_CASH){commands.execute(edit)}
        assertEquals(ops,count("operations"));assertEquals(1L,db.ledger().deposit(d)!!.revision)
        commands.execute(CloseTermDeposit(id(),d,true));set(a,0)
        expect(ErrorCode.STALE_RECORD){commands.execute(edit.copy(principal_minor=50000))}
        expect(ErrorCode.NOT_MATURED){commands.execute(edit.copy(operation_id=id(),expected_revision=2,principal_minor=50000,close_cash_linked=true,end_epoch_day=day("2027-01-01")))}
        expect(ErrorCode.INSUFFICIENT_CASH){commands.execute(edit.copy(operation_id=id(),expected_revision=2,principal_minor=50000,close_cash_linked=false))}
        assertEquals(2L,db.ledger().deposit(d)!!.revision);assertEquals("CLOSED",db.ledger().deposit(d)!!.status)
        reconcile(a,i)
    }}
    @Test fun deposit_correction_faults_and_concurrent_edits_keep_both_cash_sources_atomic() {runBlocking{
        val a=account();set(a,100000);val i=asset(a)
        val d=commands.execute(OpenTermDeposit(id(),a,"CNY",50000,e("3"),day("2026-01-01"),day("2026-04-01"),true)).id
        commands.execute(CloseTermDeposit(id(),d,true))
        val request=EditTermDeposit(id(),d,2,60000,e("4"),day("2026-01-01"),day("2026-04-01"),true,true)
        val before=db.ledger().cash_one(a,"CNY")!!.balance_minor;val ops=count("operations")
        TransactionPoint.entries.filter { it != TransactionPoint.AFTER_COST }.forEach {point->
            val failing=RoomFinancialCommands(db,clock){if(it==point)throw IllegalStateException("injected")}
            try{failing.execute(request);fail()}catch(_:IllegalStateException){}
            assertEquals(before,db.ledger().cash_one(a,"CNY")!!.balance_minor)
            assertEquals(50000L,db.ledger().deposit(d)!!.principal_minor);assertEquals(ops,count("operations"));reconcile(a,i)
        }
        val attempts=(1..2).map{async(Dispatchers.IO){runCatching{commands.execute(request.copy(operation_id=id()))}}}.awaitAll()
        assertEquals(1,attempts.count{it.isSuccess});assertEquals(3L,db.ledger().deposit(d)!!.revision);reconcile(a,i)
    }}
    @Test fun trade_correction_and_delete_preserve_later_changes_price_and_replay() {runBlocking{
        val a=account();set(a,100000);val i=asset(a)
        val original=RecordInvestmentTrade(id(),i,Direction.BUY,e("2"),e("90"),100,true)
        val trade=commands.execute(original).id
        commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("1"),e("110"),200,true))
        investments.update_price(i,e("120"))
        val correction=EditInvestmentTrade(id(),trade,1,Direction.BUY,e("3"),e("80"),300,true)
        assertEquals(commands.execute(correction),commands.execute(correction))
        assertEquals(87000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(e("12"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(e("120"),(db.ledger().instrument(db.ledger().investment(i)!!.instrument_id)!!.current_price_e5 * 1000))
        assertEquals(trade,investments.trade_page(i,null).first().id)
        assertEquals(300L,db.ledger().source_entry("TRADE",trade)!!.occurred_at_ms)
        expect(ErrorCode.STALE_RECORD){commands.execute(correction.copy(operation_id=id()))}
        expect(ErrorCode.OPERATION_CONFLICT){commands.execute(correction.copy(quantity_e8=e("4")))}
        val deletion=DeleteInvestmentTrade(id(),trade,2)
        assertEquals(commands.execute(deletion),commands.execute(deletion))
        assertEquals(111000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(e("9"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(1,investments.trade_page(i,null).size);assertNull(investments.get_trade(trade))
        assertEquals(trade,commands.execute(original).id)
        reconcile(a,i)
    }}
    @Test fun linked_toggle_and_direction_change_update_one_source_entry() {runBlocking{
        val a=account();set(a,100000);val i=asset(a)
        val trade=commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("2"),e("90"),100,false)).id
        commands.execute(EditInvestmentTrade(id(),trade,1,Direction.BUY,e("2"),e("90"),100,true))
        assertEquals(82000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        val entry=db.ledger().source_entry("TRADE",trade)!!.id
        commands.execute(EditInvestmentTrade(id(),trade,2,Direction.BUY,e("2"),e("90"),100,false))
        assertEquals(100000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertTrue(db.ledger().cash_entry(entry)!!.is_deleted)
        commands.execute(EditInvestmentTrade(id(),trade,3,Direction.SELL,e("3"),e("110"),50,true))
        assertEquals(133000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(e("7"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(entry,db.ledger().source_entry("TRADE",trade)!!.id)
        reconcile(a,i)
    }}
    @Test fun manual_cash_delta_correction_preserves_later_adjustments_and_is_idempotent() {runBlocking{
        val a=account();val first=set(a,100000).id;set(a,130000);val i=asset(a)
        val correction=EditCashEntry(id(),first,1,90000,10,"  修正初始金额  ")
        assertEquals(commands.execute(correction),commands.execute(correction))
        assertEquals(120000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals("修正初始金额",db.ledger().cash_entry(first)!!.note)
        expect(ErrorCode.STALE_RECORD){commands.execute(correction.copy(operation_id=id()))}
        val last=set(a,110000).id
        assertEquals(-10000L,db.ledger().cash_entry(last)!!.delta_minor)
        commands.execute(EditCashEntry(id(),last,1,-5000,20,""))
        assertEquals(115000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        reconcile(a,i)
    }}
    @Test fun corrections_and_deletions_recheck_funds_and_holdings_atomically() {runBlocking{
        val a=account();set(a,0);val i=asset(a,"0")
        val buy=commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("1"),1,false)).id
        commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("1"),e("1"),2,false))
        val ops=count("operations")
        expect(ErrorCode.HISTORY_CONFLICT){commands.execute(DeleteInvestmentTrade(id(),buy,1))}
        expect(ErrorCode.INSUFFICIENT_CASH){commands.execute(EditInvestmentTrade(id(),buy,1,Direction.BUY,e("1"),e("1"),1,true))}
        assertEquals(ops,count("operations"));assertEquals(1L,db.ledger().trade(buy)!!.revision)
        val seller=commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("2"),e("1"),3,false)).id
        val sale=commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("1"),e("100"),4,true)).id
        set(a,0)
        expect(ErrorCode.INSUFFICIENT_CASH){commands.execute(DeleteInvestmentTrade(id(),sale,1))}
        val source=db.ledger().source_entry("TRADE",sale)!!
        expect(ErrorCode.SOURCE_RECORD){commands.execute(EditCashEntry(id(),source.id,source.revision,0,1,""))}
        assertFalse(db.ledger().trade(sale)!!.is_deleted);assertFalse(db.ledger().trade(seller)!!.is_deleted)
        reconcile(a,i)
    }}
    @Test fun correction_faults_roll_back_source_receipts_balance_and_holding() {runBlocking{
        val a=account();val manual=set(a,100000).id;val i=asset(a)
        val trade=commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("90"),1,true)).id
        val ops=count("operations");val receipts=count("cash_movements")
        TransactionPoint.entries.forEach {point->
            val failing=RoomFinancialCommands(db,clock){if(it==point)throw IllegalStateException("injected")}
            listOf<FinancialCommand>(EditInvestmentTrade(id(),trade,1,Direction.BUY,e("2"),e("80"),2,true),
                DeleteInvestmentTrade(id(),trade,1),EditCashEntry(id(),manual,1,110000,3,"new"))
                .filter { point != TransactionPoint.AFTER_COST || it !is EditCashEntry }.forEach{request->
                try{failing.execute(request);fail()}catch(_:IllegalStateException){}
                assertEquals(91000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
                assertEquals(e("11"),db.ledger().investment(i)!!.holding_quantity_e8)
                assertEquals(1L,db.ledger().trade(trade)!!.revision);assertEquals(1L,db.ledger().cash_entry(manual)!!.revision)
                assertEquals(ops,count("operations"));assertEquals(receipts,count("cash_movements"));reconcile(a,i)
            }
        }
    }}
    @Test fun concurrent_corrections_accept_only_one_revision_and_cash_entries_sort_by_date() {runBlocking{
        val a=account();set(a,100000);val i=asset(a)
        val trade=commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("90"),100,true)).id
        val attempts=(1..2).map{async(Dispatchers.IO){runCatching{
            commands.execute(EditInvestmentTrade(id(),trade,1,Direction.BUY,e("2"),e("80"),300,true))}}}.awaitAll()
        assertEquals(1,attempts.count{it.isSuccess});assertEquals(84000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("1"),200,true))
        val source_rows=RoomCash(db.ledger()).observe_entries(a,"CNY",50).first().filter{it.source==CashSource.TRADE}
        assertEquals(listOf(300L,200L),source_rows.map{it.occurred_at_ms});reconcile(a,i)
    }}
    @Test fun accounts_cash_and_currency_are_isolated() { runBlocking {
        val a=account(); val b=account("B"); set(a,2000000); set(a,800,"USD"); set(b,900)
        set(a,1000000)
        assertEquals(1000000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(800,db.ledger().cash_one(a,"USD")!!.balance_minor)
        assertEquals(900,db.ledger().cash_one(b,"CNY")!!.balance_minor)
        assertEquals(3,count("cash_accounts"))
        accounts.save_account(a,"改名","备注")
        assertEquals("B",db.ledger().account(b)!!.name)
    } }
    @Test fun same_currency_cash_accounts_are_independent_and_trade_target_moves_atomically() { runBlocking {
        val account = account()
        commands.execute(SaveAccount(id(), account, 1, "A", "synthetic", listOf(
            CashBalanceChange("USD", 100000, null, name = "Broker cash"),
            CashBalanceChange("USD", 50000, null, name = "Savings cash")
        )))
        val cash = db.cash().cashCandidates(account, "USD")
        assertEquals(listOf("Broker cash", "Savings cash"), cash.map { it.name })
        val type = investments.save_type(null, "ETF")
        val instrument = commands.execute(SaveInstrument(id(), null, null, "QQQ", "QQQ", type,
            "USD", 20000000)).id
        val trade = commands.execute(RecordAccountTrade(id(), account, instrument, Direction.BUY,
            e("1"), e("200"), 100, true, cash[0].id)).id
        assertEquals(80000L, db.cash().cashAccount(cash[0].id)!!.balance_minor)
        assertEquals(50000L, db.cash().cashAccount(cash[1].id)!!.balance_minor)

        val otherAccount = account("Other")
        set(otherAccount, 50000, "USD")
        val wrongTarget = db.cash().cashCandidates(otherAccount, "USD").single().id
        expect(ErrorCode.WRONG_CASH_ACCOUNT) {
            commands.execute(EditInvestmentTrade(id(), trade, 1, Direction.BUY, e("1"), e("200"),
                100, true, wrongTarget))
        }
        assertEquals(80000L, db.cash().cashAccount(cash[0].id)!!.balance_minor)
        assertEquals(50000L, db.cash().cashAccount(cash[1].id)!!.balance_minor)

        commands.execute(EditInvestmentTrade(id(), trade, 1, Direction.BUY, e("1"), e("200"),
            100, true, cash[1].id))
        assertEquals(100000L, db.cash().cashAccount(cash[0].id)!!.balance_minor)
        assertEquals(30000L, db.cash().cashAccount(cash[1].id)!!.balance_minor)
        assertEquals(cash[1].id, db.trades().trade(trade)!!.cash_account_id)
        assertEquals(1L, count("investment_trades"))
        assertEquals(1L, count("investments"))
        assertFalse(db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").moveToFirst())
    } }
    @Test fun deposits_have_independent_interest_and_optional_linkage() { runBlocking {
        val a=account(); set(a,2000000)
        val one=OpenTermDeposit(id(),a,"CNY",1000000,e("3"),day("2026-01-01"),day("2026-04-01"),true)
        val d=commands.execute(one).id
        assertEquals(1000000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(7397,db.ledger().deposit(d)!!.expected_interest_minor)
        val two=commands.execute(one.copy(operation_id=id(),annual_rate_percent_e8=e("2"),cash_linked=false)).id
        assertEquals(4932,db.ledger().deposit(two)!!.expected_interest_minor)
        assertEquals(1000000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        val settle=CloseTermDeposit(id(),d,true)
        commands.execute(settle); commands.execute(settle)
        assertEquals(2007397,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        expect(ErrorCode.ALREADY_CLOSED) { commands.execute(settle.copy(operation_id=id())) }
        commands.execute(CloseTermDeposit(id(),two,false))
        assertEquals(2007397,db.ledger().cash_one(a,"CNY")!!.balance_minor)
    } }
    @Test fun maturity_is_display_only_and_early_close_fails() { runBlocking {
        val a=account(); val d=commands.execute(OpenTermDeposit(id(),a,"JPY",10000,e("3"),
            day("2026-09-01"),day("2026-10-01"),false)).id
        expect(ErrorCode.NOT_MATURED) { commands.execute(CloseTermDeposit(id(),d,true)) }
        assertNull(db.ledger().cash_one(a,"JPY")); assertEquals("OPEN",db.ledger().deposit(d)!!.status)
        assertEquals(1,count("operations"))
    } }
    @Test fun opening_and_asset_creation_retries_and_concurrent_settlement_apply_once() { runBlocking {
        val a=account();set(a,2000000)
        val opening=OpenTermDeposit(id(),a,"CNY",1000000,e("3"),day("2026-01-01"),day("2026-04-01"),true)
        val result=commands.execute(opening)
        assertEquals(result,commands.execute(opening.copy(currency_code=" cny ")))
        assertEquals(1,count("term_deposits"));assertEquals(1000000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        val type=investments.save_type(null,"合成类型")
        val creation=CreateInvestment(id(),a,"合成资产","",type,"CNY",e("10"),e("100"),e("100"))
        assertEquals(commands.execute(creation),commands.execute(creation));assertEquals(1,count("investments"))
        val attempts=(1..2).map{async(Dispatchers.IO){runCatching{commands.execute(CloseTermDeposit(id(),result.id,true))}}}.awaitAll()
        assertEquals(1,attempts.count{it.isSuccess});assertEquals(2007397,db.ledger().cash_one(a,"CNY")!!.balance_minor)
    } }
    @Test fun holding_and_cash_sum_overflow_leave_the_transaction_unchanged() { runBlocking {
        val a=account();val i=asset(a);val before=count("operations")
        expect(ErrorCode.OVERFLOW){commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,Long.MAX_VALUE,1,1,false))}
        assertEquals(e("10"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(before,count("operations"));assertEquals(0,count("investment_trades"))
        set(a,Long.MAX_VALUE)
        val d=commands.execute(OpenTermDeposit(id(),a,"CNY",10000,0,day("2026-01-01"),day("2026-04-01"),false)).id
        val operations_before=count("operations")
        expect(ErrorCode.OVERFLOW){commands.execute(CloseTermDeposit(id(),d,true))}
        assertEquals(Long.MAX_VALUE,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals("OPEN",db.ledger().deposit(d)!!.status);assertEquals(operations_before,count("operations"))
    } }
    @Test fun trade_history_price_opening_holdings_and_zero_position() { runBlocking {
        val a=account(); set(a,2000000); val i=asset(a)
        assertEquals(0,count("investment_trades")); assertEquals(2000000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("2"),e("90"),1,true))
        commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("3"),e("110"),2,true))
        assertEquals(e("9"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(e("100"),(db.ledger().instrument(db.ledger().investment(i)!!.instrument_id)!!.current_price_e5 * 1000))
        assertEquals(2015000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        investments.update_price(i,e("120"))
        assertEquals(108000,R.amount(e("9"),(db.ledger().instrument(db.ledger().investment(i)!!.instrument_id)!!.current_price_e5 * 1000),Currency.of("CNY")))
        val rows=investments.trade_page(i,null)
        assertEquals(listOf(e("110"),e("90")),rows.map{it.execution_price_e8})
        commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("9"),e("100"),3,false))
        assertEquals(0,db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(2015000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(3,investments.trade_page(i,null).size)
    } }
    @Test fun insufficient_missing_currency_and_oversell_roll_back() { runBlocking {
        val a=account(); val i=asset(a)
        val baseline=count("operations")
        expect(ErrorCode.WRONG_CASH_ACCOUNT) { commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("100"),1,true)) }
        expect(ErrorCode.INSUFFICIENT_HOLDING) { commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("11"),e("100"),1,false)) }
        expect(ErrorCode.WRONG_CASH_ACCOUNT) { commands.execute(OpenTermDeposit(id(),a,"USD",10000,e("1"),day("2026-01-01"),day("2026-04-01"),true)) }
        assertEquals(0,count("investment_trades")); assertEquals(0,count("cash_accounts"))
        assertEquals(0,count("term_deposits")); assertEquals(baseline,count("operations"))
        assertEquals(e("10"),db.ledger().investment(i)!!.holding_quantity_e8)
    } }
    @Test fun same_operation_is_idempotent_and_conflicts_rejected() { runBlocking {
        val a=account(); set(a,100000); val i=asset(a)
        val c=RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("90"),1,true)
        val first=commands.execute(c); assertEquals(first,commands.execute(c))
        expect(ErrorCode.OPERATION_CONFLICT) { commands.execute(c.copy(quantity_e8=e("2"))) }
        assertEquals(1,count("investment_trades")); assertEquals(91000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
    } }
    @Test fun transaction_faults_after_business_and_cash_leave_no_partial_writes() { runBlocking {
        val a=account(); set(a,100000); val i=asset(a); val ops=count("operations"); val movements=count("cash_movements")
        TransactionPoint.entries.forEach { point ->
            val failing=RoomFinancialCommands(db,clock) { if(it==point) throw IllegalStateException("injected") }
            try { failing.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("90"),1,true)); fail() }
            catch(_:IllegalStateException) { }
            assertEquals(100000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
            assertEquals(e("10"),db.ledger().investment(i)!!.holding_quantity_e8)
            assertEquals(0,count("investment_trades")); assertEquals(ops,count("operations")); assertEquals(movements,count("cash_movements"))
        }
    } }
    @Test fun cancellation_in_the_middle_rolls_back_and_propagates() { runBlocking {
        val a=account();set(a,100000);val i=asset(a);val ops=count("operations")
        val cancelled=RoomFinancialCommands(db,clock){if(it==TransactionPoint.AFTER_CASH)throw CancellationException("injected")}
        try { cancelled.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("90"),1,true));fail() }
        catch(_:CancellationException){}
        assertEquals(100000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        assertEquals(e("10"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(0,count("investment_trades"));assertEquals(ops,count("operations"))
    } }
    @Test fun concurrent_debits_and_sales_recheck_authoritative_state() { runBlocking {
        val a=account(); set(a,10000); val i=asset(a,"1")
        val buys=(1..2).map { async(Dispatchers.IO) { runCatching {
            commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("90"),1,true)) } } }.awaitAll()
        assertEquals(1,buys.count{it.isSuccess}); assertEquals(1000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
        val sells=(1..2).map { async(Dispatchers.IO) { runCatching {
            commands.execute(RecordInvestmentTrade(id(),i,Direction.SELL,e("2"),e("100"),2,false)) } } }.awaitAll()
        assertEquals(1,sells.count{it.isSuccess}); assertEquals(0,db.ledger().investment(i)!!.holding_quantity_e8)
    } }
    @Test fun price_updates_do_not_overwrite_concurrent_holdings_and_stale_cash_is_rejected() { runBlocking {
        val a=account(); set(a,100000); val i=asset(a); val revision=db.ledger().cash_one(a,"CNY")!!.revision
        coroutineScope {
            launch(Dispatchers.IO) { investments.update_price(i,e("120")) }
            launch(Dispatchers.IO) { commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("2"),e("90"),1,true)) }
        }
        assertEquals(e("12"),db.ledger().investment(i)!!.holding_quantity_e8)
        assertEquals(e("120"),(db.ledger().instrument(db.ledger().investment(i)!!.instrument_id)!!.current_price_e5 * 1000))
        expect(ErrorCode.STALE_BALANCE) { commands.execute(SetCashBalance(id(),a,"CNY",100000,revision)) }
        assertEquals(82000,db.ledger().cash_one(a,"CNY")!!.balance_minor)
    } }
    @Test fun keyset_paging_same_timestamp_and_holdings_reconcile() { runBlocking {
        val a=account(); val i=asset(a)
        repeat(121) { commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("1"),100,false)) }
        val ids=mutableListOf<Long>(); var cursor:TradeCursor?=null
        do { val page=investments.trade_page(i,cursor); ids.addAll(page.map{it.id})
            cursor=page.lastOrNull()?.let{TradeCursor(it.occurred_at_ms,it.id)}
        } while(cursor!=null)
        assertEquals(121,ids.size); assertEquals(121,ids.toSet().size)
        assertEquals(e("131"),db.ledger().investment(i)!!.holding_quantity_e8)
        commands.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("1"),100,false))
        assertTrue(investments.trade_page(i,null).first().id>ids.first())
    } }
    @Test fun duplicate_currency_cash_accounts_foreign_keys_and_type_maintenance() { runBlocking {
        val a=account(); set(a,100)
        db.ledger().insert_cash(CashEntity(a,"CNY",0,1,0,name="Second CNY"))
        assertEquals(2,db.cash().cashCandidates(a,"CNY").size)
        try { db.ledger().insert_cash(CashEntity(9999,"USD",0,1,0)); fail() } catch(_:android.database.sqlite.SQLiteConstraintException) {}
        val t=investments.save_type(null,"  Fund  ")
        expect(ErrorCode.DUPLICATE_TYPE) { investments.save_type(null,"fund") }
        investments.save_type(t,"基金")
        assertEquals("基金",investments.observe_types().first().single().name)
    } }
    @Test fun disk_reopen_preserves_all_relationships() { runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>(); val name="test-${id()}.db"
        var disk=Room.databaseBuilder(context,ValnookDatabase::class.java,name).addCallback(ValnookDatabase.seed).build()
        try {
            val a=RoomAccounts(disk,clock).save_account(null,"合成账户","")
            val t=RoomInvestments(disk,clock).save_type(null,"类型")
            val f=RoomFinancialCommands(disk,clock)
            f.execute(SetCashBalance(id(),a,"CNY",10000,null))
            val i=f.execute(CreateInvestment(id(),a,"资产","",t,"CNY",e("10"),e("100"),e("100"))).id
            f.execute(RecordInvestmentTrade(id(),i,Direction.BUY,e("1"),e("1"),1,true))
            val d=f.execute(OpenTermDeposit(id(),a,"CNY",1000000,e("3"),day("2026-01-01"),day("2026-04-01"),false)).id
            f.execute(CloseTermDeposit(id(),d,false))
            disk.close()
            disk=Room.databaseBuilder(context,ValnookDatabase::class.java,name).addCallback(ValnookDatabase.seed).build()
            assertEquals(9900,disk.ledger().cash_one(a,"CNY")!!.balance_minor)
            assertEquals(e("11"),disk.ledger().investment(i)!!.holding_quantity_e8)
            assertEquals(1,disk.ledger().first_trades(i,50).size)
            assertEquals("合成账户",disk.ledger().account(a)!!.name)
            assertEquals(7397,disk.ledger().deposit(d)!!.expected_interest_minor)
            assertEquals("CLOSED",disk.ledger().deposit(d)!!.status)
            disk.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use{assertFalse(it.moveToFirst())}
        } finally { disk.close(); context.deleteDatabase(name) }
    } }
}
