package dev.valnook.feature.deposits
import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.time.*
import org.junit.*
import org.junit.Assert.*
@OptIn(ExperimentalCoroutinesApi::class)
class DepositsViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun prepare(){Dispatchers.setMain(dispatcher)}
    @After fun close(){Dispatchers.resetMain()}
    @Test fun source_edit_preserves_both_link_choices_and_restores_without_automatic_write()=runTest(dispatcher) {
        val deposit=TermDeposit(999,1,Currency.of("CNY"),1000000,300000000,LocalDate.parse("2026-01-01").toEpochDay(),
            LocalDate.parse("2026-04-01").toEpochDay(),7397,true,true,true,4)
        val repo=object:DepositRepository {
            override suspend fun get_deposit(id:Long)=deposit
            override fun observe_deposits(account_id:Long,limit:Int,closed:Boolean)=flowOf(emptyList<TermDeposit>())
        }
        val requests=mutableListOf<FinancialCommand>()
        val commands=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            requests+=command;return OperationResult("TERM_DEPOSIT",999)}}
        val saved=SavedStateHandle();val clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC)
        val vm=DepositsViewModel(1,repo,commands,clock,saved);vm.prepare_source(999);vm.field("rate","4")
        assertEquals("+24.66",vm.cash_change_preview());assertEquals("true",vm.draft.value.fields["linked"])
        val restored=DepositsViewModel(1,repo,commands,clock,SavedStateHandle(saved.keys().associateWith{saved.get<Any?>(it)}))
        restored.prepare_source(999);assertTrue(requests.isEmpty());restored.submit();runCurrent()
        assertEquals(4L,(requests.single() as EditTermDeposit).expected_revision)
        assertEquals(400000000L,(requests.single() as EditTermDeposit).annual_rate_percent_e8)
    }
    @Test fun opening_preview_matches_committed_inputs_and_link_defaults_off()=runTest(dispatcher) {
        val captured=mutableListOf<FinancialCommand>()
        val repo=object:DepositRepository{override suspend fun get_deposit(id:Long):TermDeposit?=null;override fun observe_deposits(account_id:Long,limit:Int,closed:Boolean)=flowOf(emptyList<TermDeposit>())}
        val command=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult{
            captured+=command;return OperationResult("TERM",1)}}
        val vm=DepositsViewModel(1,repo,command,Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneOffset.UTC),SavedStateHandle())
        vm.begin(null);vm.field("principal","10000");vm.field("rate","3");vm.field("start","2026-01-01");vm.field("end","2026-04-01")
        assertEquals("73.97",vm.preview());assertEquals("false",vm.draft.value.fields["linked"])
        vm.submit();runCurrent();assertFalse((captured.single() as OpenTermDeposit).cash_linked)
    }
    @Test fun refreshing_midnight_changes_today_without_financial_command()=runTest(dispatcher) {
        var instant=Instant.parse("2026-09-30T23:59:59Z")
        val clock=object:Clock(){override fun getZone()=ZoneOffset.UTC;override fun withZone(zone:ZoneId)=this;override fun instant()=instant}
        val repo=object:DepositRepository{override suspend fun get_deposit(id:Long):TermDeposit?=null;override fun observe_deposits(account_id:Long,limit:Int,closed:Boolean)=flowOf(emptyList<TermDeposit>())}
        val command=object:FinancialCommands{override suspend fun execute(command:FinancialCommand):OperationResult=error("must not execute")}
        val vm=DepositsViewModel(1,repo,command,clock,SavedStateHandle());val before=vm.today.value
        instant=instant.plusSeconds(2);vm.refresh_today();assertEquals(before+1,vm.today.value);runCurrent()
    }
}
