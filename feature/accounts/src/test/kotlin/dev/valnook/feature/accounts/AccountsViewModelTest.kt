package dev.valnook.feature.accounts

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.repository.*
import dev.valnook.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val snapshot = AssetSnapshot(listOf(SavingsAccount(7, "旧名称", "旧备注", 3)),
        listOf(CashAccount(7, Currency.of("CNY"), 100, 8, id = 11, name = "人民币现金")),
        emptyList(), emptyList(), emptyList(), AppSettings())
    private val repository = object : OverviewRepository {
        override fun observeSnapshot() = flowOf(snapshot)
        override suspend fun snapshot() = snapshot
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    @Test fun atomic_account_command_keeps_stable_cash_identity() = runTest(dispatcher) {
        var request: SaveAccount? = null
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                request = command as SaveAccount
                return OperationResult("ACCOUNT", 7)
            }
        }
        val vm = AccountEditViewModel(7, repository, commands, SavedStateHandle())
        runCurrent()
        vm.changeName("新名称")
        vm.submit()
        runCurrent()
        assertEquals(7L, request!!.accountId)
        assertEquals(3L, request!!.expectedRevision)
        assertEquals("旧备注", request!!.note)
        assertEquals(listOf(CashBalanceChange("CNY", 100, 8, 11, "人民币现金", "")), request!!.cashChanges)
        assertTrue(vm.consumeSuccess())
        assertFalse(vm.consumeSuccess())
    }
    @Test fun restored_multicurrency_draft_does_not_submit_and_locked_row_cannot_change_currency() = runTest(dispatcher) {
        val requests = mutableListOf<SaveAccount>()
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                requests += command as SaveAccount
                return OperationResult("ACCOUNT", 7)
            }
        }
        val saved = SavedStateHandle()
        val vm = AccountEditViewModel(7, repository, commands, saved)
        runCurrent()
        vm.changeRow("11", currency = Currency.of("USD"), balance = "2.00")
        assertEquals("CNY", vm.state.value.rows.first().currency.code)
        vm.addRow()
        val new = vm.state.value.rows.last()
        vm.changeRow(new.key, name = "美元现金", currency = Currency.of("USD"), balance = "0")
        val restored = AccountEditViewModel(7, repository, commands, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertTrue(requests.isEmpty())
        restored.submit()
        restored.submit()
        runCurrent()
        assertEquals(1, requests.size)
        assertEquals(listOf(
            CashBalanceChange("CNY", 200, 8, 11, "人民币现金", ""),
            CashBalanceChange("USD", 0, null, null, "美元现金", "")
        ), requests.single().cashChanges)
    }
    @Test fun unknown_receipt_reconciles_original_operation_without_second_write() = runTest(dispatcher) {
        var writes = 0
        var operation: String? = null
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                writes++
                operation = command.operation_id
                throw java.io.IOException()
            }
            override suspend fun operationResult(operationId: String): OperationResult? {
                assertEquals(operation, operationId)
                return OperationResult("ACCOUNT", 7)
            }
        }
        val saved = SavedStateHandle()
        val vm = AccountEditViewModel(7, repository, commands, saved)
        runCurrent()
        vm.changeName("原请求")
        vm.submit()
        runCurrent()
        assertEquals(SubmissionPhase.UNKNOWN, vm.submission.value.phase)
        vm.changeName("不得覆盖")
        assertEquals("原请求", vm.state.value.name)
        val restored = AccountEditViewModel(7, repository, commands, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        restored.submit()
        runCurrent()
        assertEquals(1, writes)
        assertEquals(SubmissionPhase.SUCCEEDED, restored.submission.value.phase)
    }
    @Test fun stale_balance_keeps_original_revision_and_input() = runTest(dispatcher) {
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                assertEquals(8L, (command as SaveAccount).cashChanges.single().expectedRevision)
                throw DomainException(ErrorCode.STALE_BALANCE)
            }
        }
        val vm = AccountEditViewModel(7, repository, commands, SavedStateHandle())
        runCurrent()
        vm.changeRow("11", balance = "2.00")
        vm.submit()
        runCurrent()
        assertEquals(ErrorCode.STALE_BALANCE, vm.submission.value.error)
        assertEquals("2.00", vm.state.value.rows.single().balanceInput)
    }
    @Test fun invalid_row_is_rejected_before_any_command() = runTest(dispatcher) {
        val commands = object : FinancialCommands { override suspend fun execute(command: FinancialCommand): OperationResult = error("must not write") }
        val vm = AccountEditViewModel(7, repository, commands, SavedStateHandle())
        runCurrent()
        vm.addRow()
        vm.changeRow(vm.state.value.rows.last().key, balance = "invalid")
        vm.submit()
        runCurrent()
        assertEquals(SubmissionPhase.INVALID, vm.submission.value.phase)
    }
}
