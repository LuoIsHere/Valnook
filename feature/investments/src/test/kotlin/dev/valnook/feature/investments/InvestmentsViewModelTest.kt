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
    @Test fun detail_pagination_appends_and_stops_subscription_when_hidden()=runTest(dispatcher) {
        var active=0
        val cursors=mutableListOf<TradeCursor?>()
        val repository=object:InvestmentRepository by repo {
            override fun observe_trade_revision(investment_id:Long)=flow {
                active++
                try {emit(1L)
                    awaitCancellation()}finally {active--}
            }
            override suspend fun trade_page(investment_id:Long,cursor:TradeCursor?,limit:Int):List<Trade> {
                cursors+=cursor
                return (if(cursor==null)100L downTo 51L else 50L downTo 49L).map {
                    Trade(it,1,Direction.BUY,100000000,100000000,100,Currency.of("CNY"),false,it)
                }
            }
        }
        val vm=InvestmentDetailViewModel(1,1,repository)
        val collector=backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.state.collect()}
        runCurrent()
        vm.loadMore()
        runCurrent()
        assertEquals(52,vm.state.value.trades.size)
        assertEquals(TradeCursor(51,51),cursors.last())
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
