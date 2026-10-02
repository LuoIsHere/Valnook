package dev.valnook.feature.cash

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import dev.valnook.domain.command.SubmissionPhase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.time.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class CashViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private val account = CashAccount(1, Currency.of("KWD"), 5000, 2, id = 1, name = "KWD cash")
    private val entry = CashEntry(1, 1, Currency.of("KWD"), -1234, clock.millis(), CashSource.CASH_SET,
        null, null, "old", 2, cashAccountId = 1, cashAccountName = account.name)
    private val reads = object : CashRepository {
        override fun observe_cash(account_id: Long) = flowOf(listOf(account))
        override fun observeCashAccount(cashAccountId: Long) = flowOf<CashAccount?>(account.takeIf { it.id == cashAccountId })
        override fun observeCashEntries(cashAccountId: Long, limit: Int) = flowOf(listOf(entry))
        override fun observeCashEntry(cashAccountId: Long, entryId: Long) =
            flowOf<CashEntry?>(entry.takeIf { it.cashAccountId == cashAccountId && it.id == entryId })
        override fun observe_entries(account_id: Long, currency_code: String, limit: Int) = flowOf(listOf(entry))
        override fun observe_entry(account_id: Long, entry_id: Long) = flowOf<CashEntry?>(entry)
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    @Test fun signed_entry_date_note_and_draft_restore_without_automatic_write() = runTest(dispatcher) {
        val requests = mutableListOf<FinancialCommand>()
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult {
            requests += command
            return OperationResult("CASH_ENTRY", 1)
        } }
        val saved = SavedStateHandle()
        val vm = CashEntryEditViewModel(1, 1, reads, commands, clock, saved)
        runCurrent()
        vm.update { it.copy(amountInput = "1.000", note = "new", occurredAt = LocalDateTime.parse("2026-09-01T20:00")) }
        assertEquals("+0.234 KWD", vm.changePreview())
        val restored = CashEntryEditViewModel(1, 1, reads, commands, clock, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertTrue(requests.isEmpty())
        restored.submit()
        restored.submit()
        runCurrent()
        val request = requests.single() as EditCashEntry
        assertEquals(-1000L, request.delta_minor)
        assertEquals(2L, request.expected_revision)
        assertEquals("new", request.note)
        assertEquals(LocalDateTime.parse("2026-09-01T20:00").atZone(clock.zone).toInstant().toEpochMilli(), request.occurred_at_ms)
        assertTrue(restored.consumeSuccess())
        assertFalse(restored.consumeSuccess())
    }
    @Test fun unknown_write_freezes_input_and_retries_exact_command() = runTest(dispatcher) {
        val requests = mutableListOf<FinancialCommand>()
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult {
            requests += command
            if (requests.size == 1) throw java.io.IOException()
            return OperationResult("CASH_ENTRY", 1)
        } }
        val vm = CashEntryEditViewModel(1, 1, reads, commands, clock, SavedStateHandle())
        runCurrent()
        vm.submit()
        runCurrent()
        assertEquals(SubmissionPhase.UNKNOWN, vm.submission.value.phase)
        vm.update { it.copy(amountInput = "9") }
        vm.submit()
        runCurrent()
        assertEquals(requests.first(), requests.last())
    }
    @Test fun source_linked_entry_cannot_be_edited_as_manual_balance() = runTest(dispatcher) {
        val repository = object : CashRepository by reads {
            override fun observeCashEntry(cashAccountId: Long, entryId: Long) = flowOf(entry.copy(source = CashSource.TRADE))
        }
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult = error("must not write") }
        val vm = CashEntryEditViewModel(1, 1, repository, commands, clock, SavedStateHandle())
        runCurrent()
        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.failed)
    }
    @Test fun history_appends_next_page_and_subscription_cancels() = runTest(dispatcher) {
        var active = 0
        val cursors = mutableListOf<LedgerCursor?>()
        val pages = object : PagedCashRepository {
            override fun observeRevision(accountId: Long, currencyCode: String) = flow {
                active++
                try { emit(1L)
                    awaitCancellation() } finally { active-- }
            }
            override suspend fun page(accountId: Long, currencyCode: String, cursor: LedgerCursor?, size: Int): List<CashEntry> {
                cursors += cursor
                return if (cursor == null) (100L downTo 51L).map { entry.copy(id = it, occurred_at_ms = it) }
                    else listOf(entry.copy(id = 50, occurred_at_ms = 50))
            }
            override fun observeCashAccountRevision(cashAccountId: Long) = flow {
                active++
                try { emit(1L)
                    awaitCancellation() } finally { active-- }
            }
            override suspend fun cashAccountPage(cashAccountId: Long, cursor: LedgerCursor?, size: Int): List<CashEntry> {
                cursors += cursor
                return if (cursor == null) (100L downTo 51L).map { entry.copy(id = it, occurred_at_ms = it) }
                    else listOf(entry.copy(id = 50, occurred_at_ms = 50))
            }
        }
        val vm = CashViewModel(1, reads, pages, SavedStateHandle())
        vm.watchCashAccount(1)
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.entries.collect() }
        runCurrent()
        vm.load_more_entries()
        runCurrent()
        assertEquals(51, (vm.entries.value as CashLedgerState.Ready).rows.size)
        assertFalse((vm.entries.value as CashLedgerState.Ready).hasMore)
        assertEquals(LedgerCursor(51, 51), cursors.last())
        collector.cancel()
        runCurrent()
        assertEquals(0, active)
    }
    @Test fun balance_editor_accepts_negative_target_and_reports_signed_delta() = runTest(dispatcher) {
        var request: SetCashBalance? = null
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                request = command as SetCashBalance
                return OperationResult("CASH_ENTRY", 2)
            }
        }
        val vm = CashBalanceEditViewModel(1, 1, reads, commands, SavedStateHandle())
        runCurrent()
        vm.changeBalance("-2.500")
        assertEquals("-7.500 KWD", vm.changePreview())
        vm.submit(); runCurrent()
        assertEquals(-2500L, request!!.balance_minor)
    }
}
