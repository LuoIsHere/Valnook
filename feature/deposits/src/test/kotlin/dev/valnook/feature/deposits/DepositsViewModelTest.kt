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
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private val deposit = TermDeposit(999, 1, Currency.of("CNY"), 1000000, 300000000,
        LocalDate.parse("2026-01-01").toEpochDay(), LocalDate.parse("2026-04-01").toEpochDay(), 7397, true, true, true, 4)
    private val repo = object : DepositRepository {
        override suspend fun get_deposit(id: Long) = deposit
        override fun observe_deposit(account_id: Long, id: Long) = flowOf<TermDeposit?>(deposit)
        override fun observe_deposits(account_id: Long, limit: Int, closed: Boolean) = flowOf(emptyList<TermDeposit>())
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    @Test fun source_edit_preserves_both_link_choices_and_restored_draft() = runTest(dispatcher) {
        val requests = mutableListOf<FinancialCommand>()
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult {
            requests += command
            return OperationResult("TERM_DEPOSIT", 999)
        } }
        val saved = SavedStateHandle()
        val vm = DepositFormViewModel(1, DepositFormMode.EDIT, 999, repo, commands, clock, saved)
        runCurrent()
        vm.update { it.copy(rateInput = "4") }
        val restored = DepositFormViewModel(1, DepositFormMode.EDIT, 999, repo, commands, clock,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertTrue(requests.isEmpty())
        restored.submit()
        runCurrent()
        val request = requests.single() as EditTermDeposit
        assertEquals(4L, request.expected_revision)
        assertEquals(400000000L, request.annual_rate_percent_e8)
        assertTrue(request.open_cash_linked)
        assertEquals(true, request.close_cash_linked)
    }
    @Test fun opening_preview_uses_typed_inputs_and_default_cash_link_off() = runTest(dispatcher) {
        var request: OpenTermDeposit? = null
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult {
            request = command as OpenTermDeposit
            return OperationResult("TERM_DEPOSIT", 1)
        } }
        val vm = DepositFormViewModel(1, DepositFormMode.CREATE, null, repo, commands, clock, SavedStateHandle())
        runCurrent()
        vm.update { it.copy(principalInput = "10000", rateInput = "3",
            startDate = LocalDate.parse("2026-01-01"), endDate = LocalDate.parse("2026-04-01")) }
        assertEquals("73.97", vm.preview())
        vm.submit()
        runCurrent()
        assertFalse(request!!.cash_linked)
    }
    @Test fun midnight_refresh_does_not_issue_financial_command() = runTest(dispatcher) {
        var instant = Instant.parse("2026-09-30T23:59:59Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: ZoneId) = this
            override fun instant() = instant
        }
        val pages = object : PagedDepositRepository {
            override fun observeRevision(accountId: Long) = flowOf(0L)
            override suspend fun page(accountId: Long, closed: Boolean, cursor: LedgerCursor?, size: Int) = emptyList<TermDeposit>()
        }
        val vm = DepositsViewModel(1, false, pages, clock)
        val before = vm.today.value
        instant = instant.plusSeconds(2)
        vm.refresh_today()
        assertEquals(before + 1, vm.today.value)
    }
}
