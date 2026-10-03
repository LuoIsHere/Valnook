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

    @Test fun cancelKeepsSavedConfigurationAndSaveUsesStableIds() = runTest(dispatcher) {
        val repository = Repository()
        val vm = NavigationSettingsViewModel(repository, repository, SavedStateHandle())
        advanceUntilIdle()
        vm.edit()
        vm.move(NavigationItemId.STATISTICS, -1)
        vm.toggle(NavigationItemId.ACCOUNTS)
        vm.cancel()
        assertEquals(NavigationConfiguration(), vm.state.value.draft)
        assertEquals(0, repository.writes)

        vm.edit()
        vm.move(NavigationItemId.STATISTICS, -1)
        vm.toggle(NavigationItemId.ACCOUNTS)
        vm.toggle(NavigationItemId.SETTINGS)
        vm.save()
        advanceUntilIdle()
        assertEquals(1, repository.writes)
        assertFalse(NavigationItemId.ACCOUNTS in repository.value.value.navigation.visible)
        assertTrue(NavigationItemId.SETTINGS in repository.value.value.navigation.visible)
        assertEquals(NavigationItemId.STATISTICS, repository.value.value.navigation.order[1])
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
