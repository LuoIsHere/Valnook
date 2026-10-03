package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.math.BigDecimal

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val hkd = Currency.of("HKD")
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun close() { Dispatchers.resetMain() }
    private class Repository(initial: AppSettings = AppSettings()) : SettingsRepository,SettingsWriter {
        var value=initial
        private val values=MutableStateFlow(initial)
        var writes = 0
        override fun observeSettings() = values
        override suspend fun applyChange(change:SettingsChange):AppSettings {
            if(value.revision!=change.expectedRevision)throw DomainException(ErrorCode.STALE_RECORD)
            writes++
            value=when(change){
                is SaveFinancialSettings->value.copy(baseCurrency=change.baseCurrency,
                    rates=change.rates.map{it.copy(rate=it.rate.stripTrailingZeros())})
                is SaveLanguage->value.copy(language=change.language)
                is SaveGainLossColors->value.copy(gainLossColors=change.colors)
                is SaveNavigationConfiguration->value.copy(navigation=change.configuration)
            }.copy(revision=value.revision+1)
            values.value=value
            return value
        }
        fun publish(settings:AppSettings){value=settings;values.value=settings}
    }
    @Test fun changing_base_uses_only_matching_pairs_and_preserves_old_pair_meaning() = runTest(dispatcher) {
        val repository = Repository(AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.2")),
            FxRate(usd, hkd, BigDecimal("7.8"))), 2))
        val vm = SettingsViewModel(repository,repository,SavedStateHandle())
        runCurrent()
        vm.selectBase(hkd)
        assertEquals("7.8", vm.state.value.rows.single().rateInput)
        vm.updateRow(0, rate = "7.9")
        vm.saveRates()
        runCurrent()
        assertTrue(vm.state.value.saved)
        assertEquals(BigDecimal("7.2"), repository.value.rates.single { it.targetCurrency == cny }.rate)
        assertEquals(BigDecimal("7.9"), repository.value.rates.single { it.targetCurrency == hkd }.rate)
    }
    @Test fun draft_restoration_keeps_base_rate_text_and_original_revision() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val repository = Repository()
        val first = SettingsViewModel(repository,repository,saved)
        runCurrent()
        assertNull(first.state.value.settings.baseCurrency)
        first.selectBase(cny)
        first.addRate()
        first.updateRow(0, usd, "7.1234567890120")
        val restored = SettingsViewModel(repository,repository,saved)
        runCurrent()
        assertEquals("7.1234567890120", restored.state.value.rows.single().rateInput)
        restored.saveRates()
        runCurrent()
        assertTrue(restored.state.value.saved)
        assertEquals(1, repository.writes)
    }
    @Test fun invalid_fx_precision_positive_and_duplicates_never_reach_storage() = runTest(dispatcher) {
        val repository = Repository()
        val vm = SettingsViewModel(repository,repository,SavedStateHandle())
        runCurrent()
        vm.selectBase(cny)
        vm.addRate()
        vm.updateRow(0, usd, "7.1234567890123")
        vm.saveRates()
        assertEquals(ErrorCode.PRECISION, vm.state.value.error)
        vm.updateRow(0, rate = "0")
        vm.saveRates()
        assertEquals(ErrorCode.POSITIVE, vm.state.value.error)
        vm.updateRow(0, rate = "7")
        vm.addRate()
        vm.updateRow(1, usd, "7")
        vm.saveRates()
        assertEquals(ErrorCode.DUPLICATE_CURRENCY, vm.state.value.error)
        assertEquals(0, repository.writes)
    }
    @Test fun live_summary_updates_without_overwriting_a_dirty_draft_and_conflict_keeps_input() = runTest(dispatcher) {
        val repository = Repository()
        val vm = SettingsViewModel(repository,repository,SavedStateHandle())
        runCurrent()
        vm.selectBase(cny)
        vm.addRate()
        vm.updateRow(0, usd, "7.2000")
        repository.publish(AppSettings(hkd,revision=1,language=AppLanguage.ENGLISH))
        runCurrent()
        assertEquals(hkd,vm.state.value.savedSettings.baseCurrency)
        assertEquals(cny,vm.state.value.settings.baseCurrency)
        assertEquals("7.2000",vm.state.value.rows.single().rateInput)
        vm.saveRates()
        runCurrent()
        assertEquals(ErrorCode.STALE_RECORD,vm.state.value.error)
        assertEquals("7.2000",vm.state.value.rows.single().rateInput)
        assertEquals(0,repository.writes)
    }
    @Test fun new_pairs_start_at_one_and_do_not_reuse_a_rate_to_another_base() = runTest(dispatcher) {
        val repository = Repository(AppSettings(cny, listOf(FxRate(usd, hkd, BigDecimal("7.8")))))
        val vm = SettingsViewModel(repository,repository,SavedStateHandle())
        runCurrent()
        vm.addRate()
        assertEquals("1", vm.state.value.rows.single().rateInput)
        vm.updateRow(0, source = usd)
        assertEquals("1", vm.state.value.rows.single().rateInput)
        vm.saveRates()
        runCurrent()
        assertTrue(vm.state.value.saved)
        assertEquals(BigDecimal.ONE, repository.value.rates.single { it.sourceCurrency == usd && it.targetCurrency == cny }.rate)
        assertEquals(BigDecimal("7.8"), repository.value.rates.single { it.targetCurrency == hkd }.rate)
    }
}
