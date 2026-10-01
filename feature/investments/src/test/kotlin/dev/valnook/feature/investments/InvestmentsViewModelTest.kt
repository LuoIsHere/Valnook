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
    private val asset=Investment(1,1,1,"类型","资产","",Currency.of("CNY"),R.parse_e8("10"),R.parse_e8("10"),R.parse_e8("100"),0)
    private val repo=object:InvestmentRepository{
        override suspend fun get_trade(id:Long):Trade?=null
        override fun observe_trade(account_id:Long,id:Long)=flowOf<Trade?>(null)
        override fun observe_investment(id:Long)=flowOf(asset)
        override fun observe_investments(account_id:Long,limit:Int,section:InvestmentSection)=flowOf(listOf(asset))
        override fun observe_profit(id:Long)=flowOf<InvestmentProfit?>(null)
        override fun observe_types()=flowOf(listOf(AssetType(1,"类型")))
        override fun observe_trade_revision(investment_id:Long)=flowOf(0L)
        override suspend fun trade_page(investment_id:Long,cursor:TradeCursor?,limit:Int)=emptyList<Trade>()
        override suspend fun save_type(id:Long?,name:String)=1L
        override suspend fun edit_investment(id:Long,name:String,symbol:String,type_id:Long){}
        override suspend fun update_price(id:Long,price_e8:Long){}
    }
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    @Test fun source_trade_loads_directly_and_restores_edited_date_without_submitting()=runTest(dispatcher) {
        val source=Trade(999,asset.id,Direction.BUY,R.parse_e8("2"),R.parse_e8("90"),18000,Currency.of("CNY"),true,clock.millis(),4)
        val source_repo=object:InvestmentRepository by repo {
            override suspend fun get_trade(id:Long):Trade?{assertEquals(999L,id);return source}
        }
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command;return OperationResult("INVESTMENT_TRADE",999)}}
        val saved=SavedStateHandle();val vm=InvestmentsViewModel(1,source_repo,commands,clock,saved)
        vm.prepare_trade_source(999);vm.field("quantity","3");vm.field("price","80");vm.field("day","2026-09-01")
        assertEquals("-60.00",vm.cash_change_preview());assertTrue(requests.isEmpty())
        val restored=InvestmentsViewModel(1,source_repo,commands,clock,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}))
        restored.prepare_trade_source(999)
        assertEquals("2026-09-01",restored.draft.value.fields["day"])
        restored.submit();runCurrent()
        val request=requests.single() as EditInvestmentTrade
        assertEquals(4L,request.expected_revision);assertEquals(R.parse_e8("3"),request.quantity_e8)
        assertEquals(LocalDateTime.parse("2026-09-01T20:00").atZone(clock.zone).toInstant().toEpochMilli(),request.occurred_at_ms)
    }
    @Test fun delete_confirmation_retries_same_request_and_invalid_date_is_editable()=runTest(dispatcher) {
        val source=Trade(7,1,Direction.SELL,R.parse_e8("1"),R.parse_e8("110"),11000,Currency.of("CNY"),true,clock.millis())
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command;if(requests.size==1)throw java.io.IOException();return OperationResult("INVESTMENT_TRADE",7)}}
        val vm=InvestmentsViewModel(1,repo,commands,clock,SavedStateHandle())
        vm.begin("DELETE_TRADE",asset,trade=source)
        assertEquals("-110.00",vm.cash_change_preview());assertTrue(requests.isEmpty())
        vm.submit();runCurrent();vm.submit();runCurrent();assertEquals(requests[0],requests[1])
        vm.begin("EDIT_TRADE",asset,trade=source);vm.field("day","bad");vm.submit();runCurrent()
        assertEquals("DATE",vm.draft.value.error);assertFalse(vm.draft.value.locked)
    }
    @Test fun restored_detail_uses_id_without_loading_the_asset_list()=runTest(dispatcher) {
        val commands=object:FinancialCommands {
            override suspend fun execute(command:FinancialCommand):OperationResult=error("restoration must not submit")
        }
        val vm=InvestmentsViewModel(1,repo,commands,clock,SavedStateHandle(mapOf("detail_id" to asset.id)))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)){vm.detail.collect()}
        runCurrent()
        assertEquals(asset,vm.detail.value)
        assertTrue(vm.investments.value.isEmpty())
    }
    @Test fun trade_preview_and_request_timestamp_are_stable_across_retry()=runTest(dispatcher) {
        val requests=mutableListOf<RecordInvestmentTrade>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command as RecordInvestmentTrade
            if(requests.size==1)throw java.io.IOException()
            return OperationResult("TRADE",1)}}
        val vm=InvestmentsViewModel(1,repo,commands,clock,SavedStateHandle())
        vm.begin("BUY",asset);vm.field("quantity","2");vm.field("price","90")
        assertEquals("180.00",vm.amount_preview());vm.submit();runCurrent();vm.submit();runCurrent()
        assertEquals(requests[0],requests[1]);assertFalse(requests[0].cash_linked)
    }
}
