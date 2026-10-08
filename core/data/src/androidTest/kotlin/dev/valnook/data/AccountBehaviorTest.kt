package dev.valnook.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.calculation.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountBehaviorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-08T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private fun id() = UUID.randomUUID().toString()
    @Before fun prepare() { db = ValnookDatabase.inMemory(context); commands = RoomFinancialCommands(db, clock) }
    @After fun close() { db.close() }

    @Test fun visibility_and_cash_inclusion_are_independent_and_invalidate_cached_history() = runBlocking {
        val account = commands.execute(SaveAccount(id(), null, null, "Bank", "", listOf(
            CashBalanceChange("CNY", 10000, null, name="Spend"),
            CashBalanceChange("CNY", 20000, null, name="Reserve")))).id
        RoomSettings(db, clock).applyChange(SaveFinancialSettings(0, Currency.of("CNY"), emptyList()))
        val baseline = LocalDate.of(2026, 10, 1)
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=?", arrayOf(baseline.atStartOfDay(clock.zone).toInstant().toEpochMilli(), baseline.toEpochDay()))
        db.openHelper.writableDatabase.execSQL("UPDATE cash_entries SET occurred_at_ms=?", arrayOf(baseline.atStartOfDay(clock.zone).toInstant().toEpochMilli()))
        val stats = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.AVAILABLE_CASH, StatisticsPeriod(StatisticsGranularity.DAILY,2026,10))
        assertEquals(0, stats.loadSeries(request).points.first { it.date == baseline }.value!!.compareTo("300".toBigDecimal()))
        val before = RoomOverview(db).snapshot()
        val parent = before.accounts.single()
        commands.execute(SaveAccount(id(), account, parent.revision, parent.name, parent.note, before.cash.map {
            CashBalanceChange(it.currency.code,it.balance_minor,it.revision,it.id,it.name,
                includeInAvailableCash=it.name!="Reserve",showOnAccountsPage=false)
        }, showDepositSummary=false,showInvestmentSummary=false))
        val after = RoomOverview(db).snapshot()
        assertTrue(after.cash.none { it.showOnAccountsPage })
        assertFalse(after.accounts.single().showDepositSummary)
        val assets=AssetValuation.calculate(after)
        assertEquals(0, assets.total.amount.compareTo("300".toBigDecimal()))
        assertEquals(0, assets.cash.amount.compareTo("300".toBigDecimal()))
        assertEquals(0, assets.availableCash.amount.compareTo("100".toBigDecimal()))
        assertEquals(0, stats.loadSeries(request).points.first { it.date==baseline }.value!!.compareTo("100".toBigDecimal()))
        assertEquals(2, db.openHelper.readableDatabase.query("SELECT id FROM cash_entries").use { it.count })
    }

    @Test fun first_trade_is_atomic_idempotent_and_reuses_existing_instrument_position() = runBlocking {
        val account=commands.execute(SaveAccount(id(),null,null,"Broker","",listOf(CashBalanceChange("CNY",100000,null,name="Cash")))).id
        val cash=RoomOverview(db).snapshot().cash.single()
        val type=commands.execute(SaveAssetType(id(),null,"Stock")).id
        val instrument=commands.execute(SaveInstrument(id(),null,null,"Example","TEST",type,"CNY",1000000)).id
        val buy=RecordInvestmentTrade(id(),0,Direction.BUY,200000000,1000000000,clock.millis(),true,cash.id,50,account,instrument)
        val failed=RoomFinancialCommands(db,clock) { if(it==TransactionPoint.AFTER_CASH) throw IllegalStateException("test fault") }
        try { failed.execute(buy); fail("Expected rollback") } catch(_:IllegalStateException) {}
        assertTrue(RoomOverview(db).snapshot().positions.isEmpty())
        assertEquals(100000L,RoomOverview(db).snapshot().cash.single().balance_minor)
        val result=commands.execute(buy)
        assertEquals(result,commands.execute(buy))
        commands.execute(buy.copy(operation_id=id(),quantity_e8=100000000))
        val snapshot=RoomOverview(db).snapshot()
        assertEquals(1,snapshot.positions.size)
        assertEquals(300000000L,snapshot.positions.single().holding_quantity_e8)
        assertEquals(96900L,snapshot.cash.single().balance_minor)
        assertEquals(0,snapshot.positions.single().remainingCost!!.toBigDecimal().compareTo("31".toBigDecimal()))
        assertEquals(2,db.openHelper.readableDatabase.query("SELECT id FROM investment_trades").use { it.count })
    }

    @Test fun zero_credit_limit_supports_debt_and_shared_limit_but_rejects_negative() = runBlocking {
        val profile=CreditAccountInput(0,12,CreditDueRule.AfterStatementDays(20),null)
        val account=commands.execute(SaveAccount(id(),null,null,"Bank","",listOf(
            CashBalanceChange("CNY",-10000,null,name="Card",type=BalanceAccountType.CREDIT,credit=profile)))).id
        var snapshot=RoomOverview(db).snapshot()
        val root=snapshot.cash.single()
        val parent=snapshot.accounts.single()
        commands.execute(SaveAccount(id(),account,parent.revision,parent.name,"",listOf(
            CashBalanceChange("CNY",-5000,null,name="Family",type=BalanceAccountType.CREDIT,
                credit=profile.copy(creditLimitMinor=null,limitSourceAccountId=root.id)))))
        snapshot=RoomOverview(db).snapshot()
        val summary=CreditLimitCalculator.calculate(root.id,snapshot.cash)
        assertEquals(0,summary.totalLimitMinor.signum())
        assertEquals("15000",summary.overLimitMinor.toPlainString())
        assertEquals("-15000",summary.availableLimitMinor.toPlainString())
        try { commands.execute(SaveAccount(id(),null,null,"Invalid","",listOf(
            CashBalanceChange("CNY",0,null,name="Invalid",type=BalanceAccountType.CREDIT,credit=profile.copy(creditLimitMinor=-1)))))
            fail("Expected invalid limit")
        } catch(error:DomainException) { assertEquals(ErrorCode.INVALID_CREDIT_LIMIT,error.code) }
    }
}
