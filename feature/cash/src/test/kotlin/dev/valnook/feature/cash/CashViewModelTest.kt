package dev.valnook.feature.cash
import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class CashViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    private val reads=object:CashRepository{
        override fun observe_cash(account_id:Long)=flowOf(emptyList<CashBalance>())
        override fun observe_entries(account_id:Long,currency_code:String,limit:Int)=flowOf(emptyList<CashEntry>())
        override fun observe_entry(account_id:Long,entry_id:Long)=flowOf<CashEntry?>(null)
    }
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    @Test fun restored_cash_entry_uses_signed_delta_and_preserves_date_and_note()=runTest(dispatcher) {
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command;return OperationResult("CASH_ENTRY",1)}}
        val clock=java.time.Clock.fixed(java.time.Instant.parse("2026-09-30T12:00:00Z"),java.time.ZoneId.of("Asia/Hong_Kong"))
        val saved=SavedStateHandle();val vm=CashViewModel(1,reads,commands,saved,clock)
        vm.begin_entry(CashEntry(1,1,Currency.of("KWD"),-1234,clock.millis(),"CASH_SET",null,null,"old",2))
        vm.field("amount","1.000");vm.field("note","new");vm.field("day","2026-09-01")
        assertEquals("+0.234",vm.change_preview())
        val restored=CashViewModel(1,reads,commands,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}),clock)
        runCurrent();assertTrue(requests.isEmpty());restored.submit();runCurrent()
        val request=requests.single() as EditCashEntry
        assertEquals(-1000L,request.delta_minor);assertEquals(2L,request.expected_revision);assertEquals("new",request.note)
        assertEquals(java.time.LocalDateTime.parse("2026-09-01T20:00").atZone(clock.zone).toInstant().toEpochMilli(),request.occurred_at_ms)
    }
    @Test fun repeated_submit_and_restored_form_do_not_automatically_write()=runTest(dispatcher) {
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command;return OperationResult("CASH",1)}}
        val saved=SavedStateHandle()
        val vm=CashViewModel(1,reads,commands,saved)
        vm.begin(null);vm.field("amount","100")
        val restored=CashViewModel(1,reads,commands,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}))
        runCurrent();assertTrue(requests.isEmpty());assertEquals("100",restored.draft.value.fields["amount"])
        restored.submit();restored.submit();runCurrent()
        assertEquals(1,requests.size);assertTrue(restored.draft.value.completed)
        assertTrue(restored.consume_completion());assertFalse(restored.consume_completion())
    }
    @Test fun uncertain_storage_failure_locks_inputs_and_retries_same_id()=runTest(dispatcher) {
        val requests=mutableListOf<SetCashBalance>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command as SetCashBalance
            if(requests.size==1)throw java.io.IOException("uncertain")
            return OperationResult("CASH",1)}}
        val vm=CashViewModel(1,reads,commands,SavedStateHandle())
        vm.begin(null);vm.field("amount","100");vm.submit();runCurrent()
        assertTrue(vm.draft.value.locked);vm.field("amount","200")
        assertEquals("100",vm.draft.value.fields["amount"])
        vm.submit();runCurrent();assertEquals(requests[0],requests[1])
    }
    @Test fun stale_balance_preserves_input_and_revision()=runTest(dispatcher) {
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            assertEquals(8L,(command as SetCashBalance).expected_revision)
            throw DomainException(ErrorCode.STALE_BALANCE)}}
        val vm=CashViewModel(1,reads,commands,SavedStateHandle())
        vm.begin(CashBalance(1,Currency.of("CNY"),100,8));vm.field("amount","2.00");vm.submit();runCurrent()
        assertEquals("STALE_BALANCE",vm.draft.value.error);assertEquals("2.00",vm.draft.value.fields["amount"])
    }
}
