package dev.valnook.feature.investments
import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.time.*
import org.junit.*
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class InvestmentsViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneId.of("Asia/Hong_Kong"))
    private val asset=Investment(1,1,1,"类型","资产","",Currency.of("CNY"),R.parse_e8("10"),R.parse_e8("100"),0)
    private val repo=object:InvestmentRepository{
        override suspend fun get_trade(id:Long):Trade?=null
        override fun observe_trade(account_id:Long,id:Long)=flowOf<Trade?>(null)
        override fun observe_investment(id:Long)=flowOf(asset)
        override fun observe_investments(account_id:Long,limit:Int,section:InvestmentSection)=flowOf(listOf(asset))
        override fun observe_profit(id:Long)=flowOf<InvestmentProfit?>(null)
        override fun observe_types()=flowOf(listOf(AssetType(1,"类型")))
        override fun observe_trade_revision(investment_id:Long)=flowOf(0L)
        override suspend fun trade_page(investment_id:Long,cursor:TradeCursor?,limit:Int)=emptyList<Trade>()
    }
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    private val instrument=Instrument(1,"资产","QQQ",1,"类型",Currency.of("CNY"),10000000,true,1,0)
    private val instruments=object:InstrumentRepository {
        override fun observeInstruments()=flowOf(listOf(instrument))
        override fun observeInstrument(id:Long)=flowOf<Instrument?>(instrument)
    }
    private val cash=object:CashRepository {
        override fun observe_cash(account_id:Long)=flowOf(listOf(
            CashAccount(1,Currency.of("CNY"),50000,1,id=51,name="交易现金")
        ))
        override fun observeCashAccount(cashAccountId:Long)=flowOf<CashAccount?>(null)
        override fun observeCashEntries(cashAccountId:Long,limit:Int)=flowOf(emptyList<CashEntry>())
        override fun observeCashEntry(cashAccountId:Long,entryId:Long)=flowOf<CashEntry?>(null)
        override fun observe_entries(account_id:Long,currency_code:String,limit:Int)=flowOf(emptyList<CashEntry>())
        override fun observe_entry(account_id:Long,entry_id:Long)=flowOf<CashEntry?>(null)
    }
    @Test fun source_trade_load_and_typed_date_restore_do_not_submit()=runTest(dispatcher) {
        val source=Trade(999,1,Direction.BUY,R.parse_e8("2"),R.parse_e8("90"),18000,Currency.of("CNY"),
            true,clock.millis(),4,cashAccountId=51)
        val repository=object:InvestmentRepository by repo {override suspend fun get_trade(id:Long)=source}
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands {override suspend fun execute(command:FinancialCommand):OperationResult {
            requests+=command
            return OperationResult("INVESTMENT_TRADE",999)
        }}
        val saved=SavedStateHandle()
        val vm=TradeFormViewModel(1,TradeFormMode.EDIT,null,null,999,Direction.BUY,repository,instruments,cash,commands,clock,saved)
        runCurrent()
        vm.update {it.copy(quantityInput="3",executionPriceInput="80",feeInput="3.50",
            occurredAt=LocalDateTime.parse("2026-09-01T20:00"))}
        val restored=TradeFormViewModel(1,TradeFormMode.EDIT,null,null,999,Direction.BUY,repository,instruments,cash,commands,clock,
            SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}))
        runCurrent()
        assertTrue(requests.isEmpty())
        restored.submit()
        runCurrent()
        val request=requests.single() as EditInvestmentTrade
        assertEquals(4L,request.expected_revision)
        assertEquals(R.parse_e8("3"),request.quantity_e8)
        assertEquals(350L,request.fee_minor)
        assertEquals(LocalDateTime.parse("2026-09-01T20:00").atZone(clock.zone).toInstant().toEpochMilli(),request.occurred_at_ms)
    }
    @Test fun delete_is_explicit_and_unknown_result_retries_original_request()=runTest(dispatcher) {
        val source=Trade(7,1,Direction.SELL,R.parse_e8("1"),R.parse_e8("110"),11000,Currency.of("CNY"),true,clock.millis())
        val repository=object:InvestmentRepository by repo {override suspend fun get_trade(id:Long)=source}
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands {override suspend fun execute(command:FinancialCommand):OperationResult {
            requests+=command
            if(requests.size==1)throw java.io.IOException()
            return OperationResult("INVESTMENT_TRADE",7)
        }}
        val vm=TradeFormViewModel(1,TradeFormMode.DELETE,null,null,7,Direction.SELL,repository,instruments,cash,commands,clock,SavedStateHandle())
        runCurrent()
        assertTrue(requests.isEmpty())
        vm.submit()
        runCurrent()
        vm.submit()
        runCurrent()
        assertTrue(requests.first() is DeleteInvestmentTrade)
        assertEquals(requests.first(),requests.last())
    }
    private fun held(id:Long,account:Long,units:String,cost:String,realized:String="0",closed:Boolean=false)=
        Investment(id,account,1,"类型","资产","QQQ",Currency.of("CNY"),R.parse_e8(units),R.parse_e8("120"),0,
            instrumentId=1,remainingCost=cost,realizedProfit=realized,isClosed=closed)
    private fun overview(vararg positions:Investment)=object:OverviewRepository {
        val snapshot=AssetSnapshot(listOf(SavingsAccount(10,"甲",""),SavingsAccount(11,"乙",""),SavingsAccount(12,"丙","")),
            emptyList(),emptyList(),positions.toList(),listOf(instrument),AppSettings())
        override fun observeSnapshot()=flowOf(snapshot)
        override suspend fun snapshot()=snapshot
    }
    @Test fun instrument_page_adds_up_every_account_and_lists_held_ones_first()=runTest(dispatcher) {
        val vm=InstrumentDetailViewModel(1,overview(held(3,12,"0","0","30",closed=true),held(1,10,"10","1000"),held(2,11,"5","600")),
            repo,instruments,SavedStateHandle(),clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.state.collect()}
        // Built off the main thread: wait for it rather than for the test scheduler.
        val ready=vm.state.first{it!=InstrumentDetailState.Loading} as InstrumentDetailState.Ready
        assertEquals(listOf(10L,11L,12L),ready.holdings.map{it.account.id})
        val totals=ready.totals
        assertEquals(R.parse_e8("15"),totals.quantityE8)
        assertEquals(0,java.math.BigDecimal("1800").compareTo(totals.marketValue))
        assertEquals(0,java.math.BigDecimal("1600").compareTo(totals.cost))
        assertEquals(0,java.math.BigDecimal("200").compareTo(totals.unrealized))
        assertEquals(0,java.math.BigDecimal("30").compareTo(totals.realized))
        assertEquals(0,java.math.BigDecimal("12.5").compareTo(totals.unrealizedPercent))
        assertEquals("106.6666666667",totals.average!!.toPlainString())
    }
    @Test fun instrument_page_reads_the_snapshot_once_and_shows_a_failed_read()=runTest(dispatcher) {
        var reads=0
        val base=overview(held(1,10,"10","1000"))
        val counting=object:OverviewRepository by base {
            override fun observeSnapshot()=flow { reads++; emit(base.snapshot()) }
        }
        val vm=InstrumentDetailViewModel(1,counting,repo,instruments,SavedStateHandle(),clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.state.collect()}
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.trades.collect()}
        vm.state.first{it!=InstrumentDetailState.Loading}
        // The page and its account filter share one read.
        assertEquals(1,reads)
        val failing=object:OverviewRepository by base {
            override fun observeSnapshot()=flow<AssetSnapshot> { throw java.io.IOException("synthetic") }
        }
        val broken=InstrumentDetailViewModel(1,failing,repo,instruments,SavedStateHandle(),clock)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){broken.state.collect()}
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){broken.trades.collect()}
        assertEquals(InstrumentDetailState.Failed,broken.state.first{it!=InstrumentDetailState.Loading})
    }
    @Test fun instrument_trades_page_all_accounts_or_one_and_stop_when_hidden()=runTest(dispatcher) {
        var active=0
        val cursors=mutableListOf<TradeCursor?>()
        val positionPages=mutableListOf<Long>()
        val repository=object:InvestmentRepository by repo {
            override fun observeInstrumentTradeRevision(instrumentId:Long)=flow {
                active++
                try {emit("1:1")
                    awaitCancellation()}finally {active--}
            }
            override suspend fun instrumentTradeMonthPage(instrumentId:Long,month:LedgerMonth,cursor:TradeCursor?,size:Int):List<Trade> {
                cursors+=cursor
                return (if(cursor==null)100L downTo 51L else 50L downTo 49L).map {
                    Trade(it,1,Direction.BUY,100000000,100000000,100,Currency.of("CNY"),false,it)
                }
            }
            override suspend fun tradeMonthPage(positionId:Long,month:LedgerMonth,cursor:TradeCursor?,size:Int):List<Trade> {
                positionPages+=positionId
                return listOf(Trade(7,positionId,Direction.SELL,100000000,100000000,100,Currency.of("CNY"),false,7))
            }
        }
        val vm=InstrumentDetailViewModel(1,overview(held(1,10,"10","1000"),held(2,11,"5","600")),repository,instruments,
            SavedStateHandle(mapOf("ledgerMonth" to "1970-01")),clock)
        val collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.trades.collect()}
        runCurrent()
        vm.loadMore()
        runCurrent()
        assertEquals(52,vm.trades.value.rows.size)
        assertEquals(TradeCursor(51,51),cursors.last())
        // One account: that account's position only.
        vm.selectAccount(11)
        runCurrent()
        assertEquals(listOf(2L),positionPages)
        assertEquals(listOf(7L),vm.trades.value.rows.map{it.id})
        // An account without a position shows nothing rather than every trade.
        vm.selectAccount(12)
        runCurrent()
        assertTrue(vm.trades.value.rows.isEmpty())
        vm.selectAccount(null)
        runCurrent()
        assertEquals(1,active)
        assertEquals(50,vm.trades.value.rows.size)
        collector.cancel()
        runCurrent()
        assertEquals(0,active)
    }
    @Test fun creation_snapshot_and_timestamp_are_stable_across_unknown_retry()=runTest(dispatcher) {
        val requests=mutableListOf<RecordInvestmentTrade>()
        val commands=object:FinancialCommands {override suspend fun execute(command:FinancialCommand):OperationResult {
            requests+=command as RecordInvestmentTrade
            if(requests.size==1)throw java.io.IOException()
            return OperationResult("TRADE",1)
        }}
        val vm=TradeFormViewModel(1,TradeFormMode.CREATE,1,1,null,Direction.BUY,repo,instruments,cash,commands,clock,SavedStateHandle())
        runCurrent()
        vm.update {it.copy(quantityInput="2",executionPriceInput="90",feeInput="3.50")}
        assertEquals("183.50 CNY",vm.amountPreview())
        vm.update {it.copy(direction=Direction.SELL)}
        assertEquals("176.50 CNY",vm.amountPreview())
        vm.update {it.copy(direction=Direction.BUY)}
        vm.submit()
        runCurrent()
        vm.submit()
        runCurrent()
        assertEquals(requests.first(),requests.last())
        assertFalse(requests.first().cash_linked)
    }
    @Test fun catalogue_search_includes_existing_instruments_and_restores_query()=runTest(dispatcher) {
        val saved = SavedStateHandle()
        val vm = PositionCreateViewModel(instruments, saved)
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.available.collect {} }
        runCurrent()
        assertEquals(listOf(instrument), vm.available.value)
        vm.search("qqq"); runCurrent()
        assertEquals(listOf(instrument), vm.available.value)
        vm.search("missing"); runCurrent()
        assertTrue(vm.available.value.isEmpty())
        val restored = PositionCreateViewModel(instruments, saved)
        assertEquals("missing", restored.query.value)
        job.cancel()
    }
    @Test fun setting_the_price_as_current_is_off_by_default_and_only_for_a_trade_no_older_than_the_price()=runTest(dispatcher) {
        val requests=mutableListOf<RecordInvestmentTrade>()
        val commands=object:FinancialCommands { override suspend fun execute(command:FinancialCommand):OperationResult {
            requests += command as RecordInvestmentTrade
            return OperationResult("INVESTMENT_TRADE",requests.size.toLong())
        }}
        fun form()=TradeFormViewModel(1,TradeFormMode.CREATE,1,1,null,Direction.SELL,repo,instruments,cash,commands,clock,SavedStateHandle())
            .also { runCurrent(); it.update { s -> s.copy(quantityInput="1",executionPriceInput="90") } }
        // Off unless turned on; sells offer it as buys do.
        form().apply { assertTrue(canSetCurrentPrice()); submit(); runCurrent() }
        assertFalse(requests.last().setAsCurrentPrice)
        form().apply { update { it.copy(setAsCurrentPrice=true) }; submit(); runCurrent() }
        assertTrue(requests.last().setAsCurrentPrice)
        // A trade older than the current price cannot set it, even if it was turned on before the date changed.
        form().apply {
            update { it.copy(setAsCurrentPrice=true, priceUpdatedAtMs=clock.millis()+60_000) }
            assertFalse(canSetCurrentPrice())
            submit(); runCurrent()
        }
        assertFalse(requests.last().setAsCurrentPrice)
        // Editing a trade never offers it.
        assertFalse(TradeFormViewModel(1,TradeFormMode.EDIT,1,1,null,Direction.BUY,repo,instruments,cash,commands,clock,SavedStateHandle())
            .canSetCurrentPrice())
    }
    @Test fun first_trade_uses_selected_account_and_instrument_without_prior_position()=runTest(dispatcher) {
        val requests=mutableListOf<RecordInvestmentTrade>()
        val commands=object:FinancialCommands { override suspend fun execute(command:FinancialCommand):OperationResult {
            requests += command as RecordInvestmentTrade
            return OperationResult("INVESTMENT_TRADE",1)
        }}
        val vm=TradeFormViewModel(1,TradeFormMode.CREATE,1,null,null,Direction.BUY,repo,instruments,cash,commands,clock,SavedStateHandle())
        runCurrent()
        assertTrue(requests.isEmpty())
        vm.update { it.copy(quantityInput="2",executionPriceInput="90",feeInput="3.50") }
        vm.submit();runCurrent()
        val command=requests.single()
        assertEquals(0L,command.investment_id)
        assertEquals(1L,command.accountId)
        assertEquals(1L,command.instrumentId)
    }
}
