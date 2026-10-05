package dev.valnook.feature.accounts

import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.OverviewRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class AccountsReloadTest {
    @Test fun reload_recovers_a_failed_observer_without_reopening_the_page() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val attempts = AtomicInteger()
        val snapshot = AssetSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), AppSettings())
        val repository = object : OverviewRepository {
            override fun observeSnapshot() = flow {
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("synthetic read failure")
                emit(snapshot)
                awaitCancellation()
            }
            override suspend fun snapshot() = snapshot
        }
        val vm = AccountsViewModel(repository)
        val observer = launch(Dispatchers.Unconfined) { vm.state.collect() }
        try {
            withTimeout(5_000) { vm.state.first { it == AccountsState.Failed } }
            vm.reload()
            val ready = withTimeout(5_000) { vm.state.first { it is AccountsState.Ready } } as AccountsState.Ready
            assertEquals(snapshot, ready.snapshot)
            assertEquals(2, attempts.get())
            observer.cancelAndJoin()
            delay(30)
            val returned = mutableListOf<AccountsState>()
            val returning = launch(Dispatchers.Unconfined) { vm.state.collect { returned += it } }
            try {
                withTimeout(5_000) { while (attempts.get() < 3) delay(10) }
                delay(30)
                assertFalse("Returning to a root must retain its ready subtree", returned.any { it == AccountsState.Loading })
            } finally { returning.cancelAndJoin() }
        } finally {
            observer.cancelAndJoin()
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
