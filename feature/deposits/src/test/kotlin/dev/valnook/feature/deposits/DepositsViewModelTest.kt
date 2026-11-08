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
        LocalDate.parse("2026-01-01").toEpochDay(), LocalDate.parse("2026-04-01").toEpochDay(), 7397,
        true, true, true, 4, openCashAccountId = 51, closeCashAccountId = 51)
    private val repo = object : DepositRepository {
        override suspend fun get_deposit(id: Long) = deposit
        override fun observe_deposit(account_id: Long, id: Long) = flowOf<TermDeposit?>(deposit)
        override fun observe_deposits(account_id: Long, limit: Int, closed: Boolean) = flowOf(emptyList<TermDeposit>())
    }
    private val cash = object : CashRepository {
        override fun observe_cash(account_id: Long) = flowOf(listOf(
            CashAccount(1, Currency.of("CNY"), 2_000_000, 1, id = 51, name = "结算现金")
        ))
        override fun observeCashAccount(cashAccountId: Long) = flowOf<CashAccount?>(null)
        override fun observeCashEntries(cashAccountId: Long, limit: Int) = flowOf(emptyList<CashEntry>())
        override fun observeCashEntry(cashAccountId: Long, entryId: Long) = flowOf<CashEntry?>(null)
        override fun observe_entries(account_id: Long, currency_code: String, limit: Int) = flowOf(emptyList<CashEntry>())
        override fun observe_entry(account_id: Long, entry_id: Long) = flowOf<CashEntry?>(null)
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    @Test fun changing_the_currency_drops_a_cash_account_of_the_old_one() = runTest(dispatcher) {
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand) = OperationResult("TERM", 1) }
        val vm = DepositFormViewModel(1, DepositFormMode.CREATE, null, repo, cash, commands, clock, SavedStateHandle())
        val listening = backgroundScope.launch { vm.cashAccounts.collect {} }
        runCurrent()
        vm.setOpenCashLinked(true)
        assertEquals(51L, vm.state.value.cashAccountId)
        // No USD cash here: the CNY account is not kept for a USD deposit.
        vm.changeCurrency(Currency.of("USD"))
        assertEquals(Currency.of("USD"), vm.state.value.currency)
        assertTrue(vm.state.value.cashLinked)
        assertNull(vm.state.value.cashAccountId)
        // Back to CNY, its only account is chosen again.
        vm.changeCurrency(Currency.of("CNY"))
        assertEquals(51L, vm.state.value.cashAccountId)
        listening.cancel()
    }
    @Test fun source_edit_preserves_both_link_choices_and_restored_draft() = runTest(dispatcher) {
        val requests = mutableListOf<FinancialCommand>()
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult {
            requests += command
            return OperationResult("TERM_DEPOSIT", 999)
        } }
        val saved = SavedStateHandle()
        val vm = DepositFormViewModel(1, DepositFormMode.EDIT, 999, repo, cash, commands, clock, saved)
        runCurrent()
        vm.update { it.copy(rateInput = "4") }
        val restored = DepositFormViewModel(1, DepositFormMode.EDIT, 999, repo, cash, commands, clock,
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
        val vm = DepositFormViewModel(1, DepositFormMode.CREATE, null, repo, cash, commands, clock, SavedStateHandle())
        runCurrent()
        vm.update { it.copy(principalInput = "10000", rateInput = "3",
            startDate = LocalDate.parse("2026-01-01"), endDate = LocalDate.parse("2026-04-01")) }
        assertEquals("73.97", vm.preview())
        vm.submit()
        runCurrent()
        assertFalse(request!!.cash_linked)
    }
    @Test fun quick_terms_set_the_end_and_follow_a_moved_start() = runTest(dispatcher) {
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand) =
            OperationResult("TERM_DEPOSIT", 1) }
        val vm = DepositFormViewModel(1, DepositFormMode.CREATE, null, repo, cash, commands, clock, SavedStateHandle())
        runCurrent()
        vm.update { it.copy(principalInput = "4000", rateInput = "2.6") }
        vm.applyTerm(6)
        assertEquals(LocalDate.parse("2027-03-30"), vm.state.value.endDate)
        // A quick term keeps its length when the start moves.
        vm.changeStart(LocalDate.parse("2026-10-31"))
        assertEquals(LocalDate.parse("2027-04-30"), vm.state.value.endDate)
        val summary = vm.summary()
        assertEquals(181L, summary.days)
        assertEquals(5157L, summary.interestMinor)
        assertEquals(405157L, summary.totalMinor)
        // From a month's end it is still a six-month term, and it keeps following the start.
        assertEquals(6, wholeMonths(vm.state.value.startDate, vm.state.value.endDate))
        assertEquals(12, wholeMonths(LocalDate.parse("2026-08-31"), LocalDate.parse("2027-08-31")))
        assertEquals(3, wholeMonths(LocalDate.parse("2026-11-30"), LocalDate.parse("2027-02-28")))
        vm.changeStart(LocalDate.parse("2026-11-30"))
        assertEquals(LocalDate.parse("2027-05-30"), vm.state.value.endDate)
        vm.changeStart(LocalDate.parse("2026-10-31"))
        // An end chosen by hand stays where it is.
        vm.changeEnd(LocalDate.parse("2027-01-15"))
        vm.changeStart(LocalDate.parse("2026-11-01"))
        assertEquals(LocalDate.parse("2027-01-15"), vm.state.value.endDate)
        assertNull(wholeMonths(vm.state.value.startDate, vm.state.value.endDate))
        // An input still being typed leaves only the days.
        vm.update { it.copy(rateInput = "abc") }
        assertEquals(DepositFormSummary(75, null, null), vm.summary())
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
