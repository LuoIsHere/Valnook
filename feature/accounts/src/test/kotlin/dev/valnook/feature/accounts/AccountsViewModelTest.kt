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
    @Test fun zero_limit_and_preferences_survive_draft_restore_and_invalid_fields_keep_row_identity() = runTest(dispatcher) {
        var request:SaveAccount?=null
        val commands=object:FinancialCommands { override suspend fun execute(command:FinancialCommand):OperationResult {
            request=command as SaveAccount;return OperationResult("ACCOUNT",8)
        }}
        val saved=SavedStateHandle()
        val vm=AccountEditViewModel(null,repository,commands,saved)
        runCurrent();vm.changeName("Bank");vm.addRow()
        val key=vm.state.value.rows.single().key
        vm.changeRow(key,type=BalanceAccountType.CREDIT,creditLimit="-1",statementDay="32")
        vm.submit();runCurrent()
        assertNull(request)
        assertTrue(vm.state.value.fieldErrors.keys.containsAll(listOf("$key:name","$key:limit","$key:statement")))
        vm.changeRow(key,name="Card",creditLimit="0",statementDay="12",showOnAccountsPage=false,includeInAvailableCash=false)
        vm.changeVisibility(deposits=false,investments=false)
        val restored=AccountEditViewModel(null,repository,commands,SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent();restored.submit();runCurrent()
        assertEquals(0L,request!!.cashChanges.single().credit!!.creditLimitMinor)
        assertEquals(false,request!!.cashChanges.single().showOnAccountsPage)
        assertEquals(false,request!!.cashChanges.single().includeInAvailableCash)
        assertEquals(false,request!!.showDepositSummary)
        assertEquals(false,request!!.showInvestmentSummary)
    }
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
        assertEquals(listOf(CashBalanceChange("CNY", 100, 8, 11, "人民币现金", "", displayOrder = 0, includeInAvailableCash = true, showOnAccountsPage = true)), request!!.cashChanges)
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
        assertEquals("", new.nameInput)
        vm.changeRow(new.key, currency = Currency.of("USD"))
        assertEquals("", vm.state.value.rows.last().nameInput)
        vm.changeRow(new.key, name = "美元现金", balance = "0")
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
        ).mapIndexed { index, row -> row.copy(displayOrder = index.toLong(), includeInAvailableCash = true, showOnAccountsPage = true) }, requests.single().cashChanges)
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
    @Test fun account_editor_submits_negative_cash_balance() = runTest(dispatcher) {
        var request: SaveAccount? = null
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                request = command as SaveAccount
                return OperationResult("ACCOUNT", 7)
            }
        }
        val vm = AccountEditViewModel(7, repository, commands, SavedStateHandle())
        runCurrent()
        vm.changeRow("11", balance = "-12.34")
        vm.submit(); runCurrent()
        assertEquals(-1234L, request!!.cashChanges.single().balanceMinor)
    }

    @Test fun restored_draft_refreshes_credit_limit_sources_without_overwriting_input() = runTest(dispatcher) {
        val creditSnapshot = snapshot.copy(
            accounts = snapshot.accounts + SavingsAccount(8, "其他主账户", "", 1),
            cash = snapshot.cash + listOf(CashAccount(
                account_id = 7,
                currency = Currency.of("CNY"),
                balance_minor = -2_000,
                revision = 2,
                id = 12,
                name = "共享额度主账户",
                creditProfile = CreditAccountProfile(
                    creditLimitMinor = 100_000,
                    statementDay = 12,
                    dueRule = CreditDueRule.AfterStatementDays(20),
                    limitSourceAccountId = null
                )
            ), CashAccount(
                account_id = 8,
                currency = Currency.of("CNY"),
                balance_minor = -1_000,
                revision = 1,
                id = 13,
                name = "其他主账户信用卡",
                creditProfile = CreditAccountProfile(100_000, 12,
                    CreditDueRule.AfterStatementDays(20), null)
            ))
        )
        val creditRepository = object : OverviewRepository {
            override fun observeSnapshot() = flowOf(creditSnapshot)
            override suspend fun snapshot() = creditSnapshot
        }
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand) = OperationResult("ACCOUNT", 7)
        }
        val saved = SavedStateHandle()
        val original = AccountEditViewModel(7, creditRepository, commands, saved)
        runCurrent()
        original.changeName("未保存草稿")

        val restored = AccountEditViewModel(
            7,
            creditRepository,
            commands,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        )
        runCurrent()

        assertEquals("未保存草稿", restored.state.value.name)
        assertEquals(listOf(12L), restored.state.value.creditSources.map { it.id })
        assertEquals("旧名称 · 共享额度主账户", restored.state.value.creditSources.single().label)
    }

    @Test fun existing_balance_account_delete_uses_guarded_command_identity() = runTest(dispatcher) {
        var request: DeleteBalanceAccount? = null
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                request = command as DeleteBalanceAccount
                return OperationResult("BALANCE_ACCOUNT", command.balanceAccountId)
            }
        }
        val vm = AccountEditViewModel(7, repository, commands, SavedStateHandle())
        runCurrent()

        vm.deleteRow("11")
        runCurrent()

        assertEquals(7L, request?.accountId)
        assertEquals(11L, request?.balanceAccountId)
        assertEquals(8L, request?.expectedRevision)
        assertEquals(SubmissionPhase.SUCCEEDED, vm.submission.value.phase)
    }
}
