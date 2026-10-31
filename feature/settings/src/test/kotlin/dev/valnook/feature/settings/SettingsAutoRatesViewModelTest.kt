package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.fx.FxRateSync
import dev.valnook.domain.fx.FxSyncNotice
import dev.valnook.domain.fx.FxSyncStatus
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAutoRatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val hkd = Currency.of("HKD")
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }

    private class Repository(initial: AppSettings) : SettingsRepository, SettingsWriter {
        val values = MutableStateFlow(initial)
        val changes = mutableListOf<SettingsChange>()
        override fun observeSettings() = values
        override suspend fun applyChange(change: SettingsChange): AppSettings {
            if (values.value.revision != change.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
            changes += change
            val next = when (change) {
                is SaveFinancialSettings -> values.value.copy(baseCurrency = change.baseCurrency, rates = change.rates)
                else -> values.value
            }.copy(revision = values.value.revision + 1)
            values.value = next
            return next
        }
    }

    /** Records calls; the switch follows them like the real engine would. */
    private class Sync(initial: FxSyncStatus) : FxRateSync {
        val status = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        override fun observeStatus() = status
        override fun observeNotice() = flowOf<FxSyncNotice?>(null)
        override suspend fun consumeNotice(id: String) = Unit
        override suspend fun refreshNow() { calls += "refresh" }
        override suspend fun setAutoEnabled(on: Boolean) { calls += "auto:$on"; status.value = status.value.copy(autoEnabled = on) }
        override suspend fun dismissNotice() { calls += "dismiss"; status.value = status.value.copy(noticeDismissed = true) }
    }

    private val manual = AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.1")), FxRate(cny, usd, BigDecimal("0.14"))), 3)

    @Test fun automatic_mode_shows_the_stored_rates_read_only() = runTest(dispatcher) {
        val repository = Repository(manual)
        val sync = Sync(FxSyncStatus(available = true, autoEnabled = true))
        val vm = SettingsViewModel(repository, repository, SavedStateHandle(), sync)
        runCurrent()
        assertTrue(vm.state.value.automatic)
        // A sync replaced the current rates and moved the revision.
        repository.values.value = manual.copy(rates = listOf(FxRate(usd, cny, BigDecimal("6.66")), FxRate(cny, usd, BigDecimal("0.14"))), revision = 4)
        runCurrent()
        assertEquals(listOf(FxRateDraft(usd, "6.66")), vm.state.value.shownRows)
        vm.refreshRates()
        runCurrent()
        assertEquals(listOf("refresh"), sync.calls)
    }

    @Test fun turning_on_shows_the_notice_and_accepting_can_dismiss_it_for_good() = runTest(dispatcher) {
        val repository = Repository(manual)
        val sync = Sync(FxSyncStatus(available = true))
        val vm = SettingsViewModel(repository, repository, SavedStateHandle(), sync)
        runCurrent()
        vm.requestAutomatic(true)
        assertEquals(AutoRatesPrompt.NETWORK_NOTICE, vm.state.value.autoPrompt)
        vm.cancelAutomatic()
        runCurrent()
        assertNull(vm.state.value.autoPrompt)
        assertTrue(sync.calls.isEmpty())

        vm.requestAutomatic(true)
        vm.acceptNetworkNotice(dontShowAgain = true)
        runCurrent()
        assertEquals(listOf("dismiss", "auto:true"), sync.calls)
        assertTrue(vm.state.value.automatic)

        // Off is immediate and leaves the last automatic values to edit; on again skips the dismissed notice.
        repository.values.value = manual.copy(rates = listOf(FxRate(usd, cny, BigDecimal("6.66"))), revision = 5)
        runCurrent()
        vm.requestAutomatic(false)
        runCurrent()
        assertFalse(vm.state.value.automatic)
        assertEquals(listOf(FxRateDraft(usd, "6.66")), vm.state.value.rows)
        assertEquals(5, vm.state.value.baselineRevision)
        vm.requestAutomatic(true)
        runCurrent()
        assertNull(vm.state.value.autoPrompt)
        assertEquals(listOf("dismiss", "auto:true", "auto:false", "auto:true"), sync.calls)
    }

    @Test fun unsaved_edits_are_confirmed_before_the_notice() = runTest(dispatcher) {
        val repository = Repository(manual)
        val sync = Sync(FxSyncStatus(available = true))
        val vm = SettingsViewModel(repository, repository, SavedStateHandle(), sync)
        runCurrent()
        vm.updateRow(0, rate = "9")
        vm.requestAutomatic(true)
        assertEquals(AutoRatesPrompt.DISCARD_CHANGES, vm.state.value.autoPrompt)
        vm.discardForAutomatic()
        assertEquals(AutoRatesPrompt.NETWORK_NOTICE, vm.state.value.autoPrompt)
        assertFalse(vm.state.value.dirty)
        assertEquals("7.1", vm.state.value.rows.single { it.sourceCurrency == usd }.rateInput)
        vm.acceptNetworkNotice(dontShowAgain = false)
        runCurrent()
        assertEquals(listOf("auto:true"), sync.calls)
        assertTrue(repository.changes.isEmpty())
    }

    @Test fun demo_cannot_switch() = runTest(dispatcher) {
        val repository = Repository(manual)
        val sync = Sync(FxSyncStatus(available = false))
        val vm = SettingsViewModel(repository, repository, SavedStateHandle(), sync)
        runCurrent()
        vm.requestAutomatic(true)
        runCurrent()
        assertNull(vm.state.value.autoPrompt)
        assertTrue(sync.calls.isEmpty())
        assertFalse(vm.state.value.automatic)
    }

    @Test fun new_base_in_automatic_mode_is_saved_at_once_with_the_latest_revision() = runTest(dispatcher) {
        val repository = Repository(manual)
        val sync = Sync(FxSyncStatus(available = true, autoEnabled = true))
        val vm = SettingsViewModel(repository, repository, SavedStateHandle(), sync)
        runCurrent()
        repository.values.value = manual.copy(revision = 7)   // a sync moved the revision
        runCurrent()
        vm.selectBase(hkd)
        runCurrent()
        val saved = repository.changes.single() as SaveFinancialSettings
        assertEquals(7, saved.expectedRevision)
        assertEquals(hkd, saved.baseCurrency)
        assertEquals(manual.rates, saved.rates)
        assertNull(vm.state.value.error)
    }
}
