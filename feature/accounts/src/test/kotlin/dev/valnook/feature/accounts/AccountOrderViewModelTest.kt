package dev.valnook.feature.accounts

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountOrderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val accounts = listOf(SavingsAccount(1, "A", "", 1), SavingsAccount(2, "B", "", 1))
    private val snapshot = AssetSnapshot(accounts,
        listOf(CashAccount(1, Currency.of("CNY"), 100, 1, id = 3, name = "CNY")),
        emptyList(), emptyList(), emptyList(), AppSettings())
    private val repository = object : OverviewRepository {
        override fun observeSnapshot() = flowOf(snapshot)
        override suspend fun snapshot() = snapshot
    }
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }

    @Test fun restored_order_stays_a_draft_until_saved_and_rejects_duplicate_ids() = runTest(dispatcher) {
        val writes = mutableListOf<Pair<List<Long>, List<Long>>>()
        val writer = object : AccountOrderWriter {
            override suspend fun saveOrder(expectedOrder: List<Long>, orderedIds: List<Long>) { writes += expectedOrder to orderedIds }
        }
        val saved = SavedStateHandle()
        val vm = AccountOrderViewModel(repository, writer, saved)
        runCurrent()
        vm.reorder(listOf("2", "1"))
        vm.reorder(listOf("1", "1"))
        assertEquals(listOf(2L, 1L), vm.state.value.accounts.map { it.id })
        val restored = AccountOrderViewModel(repository, writer,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertTrue(writes.isEmpty())
        restored.save(); restored.save(); runCurrent()
        assertEquals(listOf(listOf(1L, 2L) to listOf(2L, 1L)), writes)
        assertTrue(restored.state.value.saved)
    }

    @Test fun rejected_order_can_be_discarded_without_overwriting_accounts() = runTest(dispatcher) {
        val writer = object : AccountOrderWriter {
            override suspend fun saveOrder(expectedOrder: List<Long>, orderedIds: List<Long>) {
                throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
        val vm = AccountOrderViewModel(repository, writer, SavedStateHandle())
        runCurrent(); vm.reorder(listOf("2", "1")); vm.save(); runCurrent()
        assertTrue(vm.state.value.error); assertFalse(vm.state.value.saved)
        vm.discardAndReload(); runCurrent()
        assertEquals(accounts, vm.state.value.accounts); assertFalse(vm.state.value.error)
    }

    @Test fun subaccount_reorder_preserves_unsaved_fields_new_rows_and_restored_draft() = runTest(dispatcher) {
        var request: SaveAccount? = null
        val commands = object : FinancialCommands {
            override suspend fun execute(command: FinancialCommand): OperationResult {
                request = command as SaveAccount; return OperationResult("ACCOUNT", 1)
            }
        }
        val saved = SavedStateHandle()
        val vm = AccountEditViewModel(1, repository, commands, saved)
        runCurrent()
        vm.changeRow("3", note = "draft", balance = "-2.00")
        vm.addRow()
        val added = vm.state.value.rows.last().key
        vm.changeRow(added, name = "USD draft", currency = Currency.of("USD"))
        vm.reorderRows(listOf(added, "3"))
        vm.reorderRows(listOf("3", "3"))
        val restored = AccountEditViewModel(1, repository, commands,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        runCurrent()
        assertNull(request)
        assertEquals(listOf(added, "3"), restored.state.value.rows.map { it.key })
        assertEquals("draft", restored.state.value.rows.last().noteInput)
        restored.submit(); runCurrent()
        assertEquals(listOf(0L, 1L), request!!.cashChanges.map { it.displayOrder })
        assertEquals(-200L, request!!.cashChanges.last().balanceMinor)
        assertEquals("USD", request!!.cashChanges.first().currencyCode)
    }
}
