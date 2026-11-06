package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun changesStayInTheDraftUntilSavedAndSettingsCannotBeHidden() = runTest(dispatcher) {
        val repository = Repository()
        val vm = NavigationSettingsViewModel(repository, repository, SavedStateHandle())
        advanceUntilIdle()
        assertFalse(vm.state.value.dirty)
        vm.hide(NavigationItemId.ACCOUNTS)
        vm.hide(NavigationItemId.SETTINGS)
        vm.reorderShown(listOf(NavigationItemId.STATISTICS, NavigationItemId.WALLET,
            NavigationItemId.INVESTMENTS, NavigationItemId.SETTINGS))
        assertEquals(0, repository.writes)
        assertTrue(vm.state.value.dirty)
        assertEquals(listOf(NavigationItemId.ACCOUNTS), vm.state.value.hidden)
        assertTrue(NavigationItemId.SETTINGS in vm.state.value.draft.visible)

        vm.save()
        advanceUntilIdle()
        assertEquals(1, repository.writes)
        assertFalse(vm.state.value.dirty)
        assertEquals(listOf(NavigationItemId.STATISTICS, NavigationItemId.WALLET, NavigationItemId.INVESTMENTS,
            NavigationItemId.SETTINGS, NavigationItemId.ACCOUNTS), repository.value.value.navigation.order)
        assertFalse(NavigationItemId.ACCOUNTS in repository.value.value.navigation.visible)
    }

    @Test fun aShownPageGoesToTheEndAndAReorderMustKeepTheSamePages() = runTest(dispatcher) {
        val repository = Repository()
        val vm = NavigationSettingsViewModel(repository, repository, SavedStateHandle())
        advanceUntilIdle()
        vm.hide(NavigationItemId.WALLET)
        vm.show(NavigationItemId.WALLET)
        assertEquals(NavigationItemId.WALLET, vm.state.value.shown.last())
        val before = vm.state.value.draft
        vm.reorderShown(listOf(NavigationItemId.SETTINGS))
        assertEquals(before, vm.state.value.draft)
        vm.save()
        advanceUntilIdle()
        assertEquals(1, repository.writes)
    }

    @Test fun withoutChangesThePageFollowsOtherWritersButADraftIsKept() = runTest(dispatcher) {
        val repository = Repository()
        val vm = NavigationSettingsViewModel(repository, repository, SavedStateHandle())
        advanceUntilIdle()
        val elsewhere = NavigationConfiguration(visible = NavigationItemId.entries.toSet() - NavigationItemId.WALLET)
        repository.value.value = repository.value.value.copy(revision = 5, navigation = elsewhere)
        advanceUntilIdle()
        assertEquals(elsewhere, vm.state.value.draft)
        assertEquals(5, vm.state.value.baselineRevision)

        vm.hide(NavigationItemId.STATISTICS)
        val draft = vm.state.value.draft
        repository.value.value = repository.value.value.copy(revision = 6, navigation = NavigationConfiguration())
        advanceUntilIdle()
        assertEquals(draft, vm.state.value.draft)
        assertEquals(5, vm.state.value.baselineRevision)
    }

    private class Repository : SettingsRepository, SettingsWriter {
        val value = MutableStateFlow(AppSettings())
        var writes = 0
        override fun observeSettings(): Flow<AppSettings> = value
        override suspend fun applyChange(change: SettingsChange): AppSettings {
            val navigation = (change as SaveNavigationConfiguration).configuration
            writes++
            return value.value.copy(revision = value.value.revision + 1, navigation = navigation)
                .also { value.value = it }
        }
    }
}
