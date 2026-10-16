package dev.valnook.feature.accounts

import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.accounts.AccountGroupsViewModel.Companion.accountKey
import dev.valnook.feature.accounts.AccountGroupsViewModel.Companion.groupKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountGroupsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val default = AccountGroup(AccountGroup.DEFAULT_ID, "", 0)
    private val brokers = AccountGroup(5, "Brokers", 1)
    private fun account(id: Long, group: AccountGroup) = SavingsAccount(id, "A$id", "", groupId = group.id)
    private val database = MutableStateFlow(AssetSnapshot(
        listOf(account(1, default), account(2, default), account(3, brokers)),
        emptyList(), emptyList(), emptyList(), emptyList(), AppSettings(), listOf(default, brokers)))
    private val repository = object : OverviewRepository {
        override fun observeSnapshot() = database
        override suspend fun snapshot() = database.value
    }

    /** Records calls; [gate] holds a write open so the in-between state can be checked. */
    private class FakeWriter : AccountGroupWriter {
        val calls = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var fail = false
        private suspend fun record(call: String) {
            calls += call
            gate?.await()
            if (fail) throw DomainException(ErrorCode.STALE_RECORD)
        }
        override suspend fun create(name: String): Long { record("create $name"); return 9 }
        override suspend fun rename(groupId: Long, name: String) = record("rename $groupId $name")
        override suspend fun delete(groupId: Long) = record("delete $groupId")
        override suspend fun reorderGroups(orderedIds: List<Long>) = record("groups $orderedIds")
        override suspend fun moveAccounts(groupId: Long, accountIds: List<Long>) = record("move $groupId $accountIds")
        override suspend fun reorderAccounts(groupId: Long, orderedIds: List<Long>) = record("accounts $groupId $orderedIds")
        override suspend fun setCollapsed(groupId: Long, collapsed: Boolean) = record("collapse $groupId $collapsed")
    }

    private fun keys(vararg sections: Pair<Long, List<Long>>) =
        sections.flatMap { (group, accounts) -> listOf(groupKey(group)) + accounts.map(::accountKey) }

    private fun ids(vm: AccountGroupsViewModel) = vm.state.value.sections.map { section -> section.items.map { it.id } }

    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }

    @Test fun reorder_saves_only_the_changed_group_and_shows_the_new_order_until_the_database_reports_it() = runTest(dispatcher) {
        val writer = FakeWriter().apply { gate = CompletableDeferred() }
        val vm = AccountGroupsViewModel(repository, writer)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), ids(vm))

        vm.reorderAccounts(keys(default.id to listOf(2, 1), brokers.id to listOf(3)))
        runCurrent()
        assertEquals(listOf("accounts 1 [2, 1]"), writer.calls)
        assertEquals(listOf(listOf(2L, 1L), listOf(3L)), ids(vm))
        assertTrue(vm.state.value.busy)

        database.value = database.value.copy(accounts = listOf(account(2, default), account(1, default), account(3, brokers)))
        writer.gate!!.complete(Unit)
        runCurrent()
        assertFalse(vm.state.value.busy)
        assertEquals(listOf(listOf(2L, 1L), listOf(3L)), ids(vm))
    }

    @Test fun failed_write_returns_to_the_stored_order_and_reports_it() = runTest(dispatcher) {
        val writer = FakeWriter().apply { fail = true }
        val vm = AccountGroupsViewModel(repository, writer)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.reorderGroups(listOf(brokers.id, default.id))
        runCurrent()
        assertTrue(vm.state.value.saveFailed)
        assertEquals(listOf(default.id, brokers.id), vm.state.value.sections.map { it.group.id })
    }

    @Test fun keys_that_move_an_account_into_another_group_are_ignored() = runTest(dispatcher) {
        val writer = FakeWriter()
        val vm = AccountGroupsViewModel(repository, writer)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.reorderAccounts(keys(default.id to listOf(1), brokers.id to listOf(2, 3)))
        vm.reorderGroups(listOf(brokers.id)) // Missing a group: stale list.
        runCurrent()
        assertTrue(writer.calls.isEmpty())
    }

    @Test fun group_actions_write_through_once_each() = runTest(dispatcher) {
        val writer = FakeWriter()
        val vm = AccountGroupsViewModel(repository, writer)
        backgroundScope.launch { vm.state.collect {} }
        runCurrent()
        vm.create("Cards"); runCurrent()
        vm.addAccounts(brokers, listOf(1, 2)); runCurrent()
        vm.rename(brokers, "Brokerage"); runCurrent()
        vm.delete(brokers); runCurrent()
        assertEquals(listOf("create Cards", "move 5 [1, 2]", "rename 5 Brokerage", "delete 5"), writer.calls)
    }
}
