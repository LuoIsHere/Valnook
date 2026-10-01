package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
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
    private class Repository(var value: AppSettings = AppSettings()) : SettingsRepository {
        var writes = 0
        var loseReceipt = false
        override fun observeSettings() = flowOf(value)
        override suspend fun saveSettings(settings: AppSettings, expectedRevision: Long) {
            if (value.revision != expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
            writes++
            value = settings.copy(revision = expectedRevision + 1,
                rates = settings.rates.map { it.copy(rate = it.rate.stripTrailingZeros()) })
            if (loseReceipt) throw IllegalStateException("synthetic lost receipt")
        }
    }
    @Test fun changing_base_uses_only_matching_pairs_and_preserves_old_pair_meaning() = runTest(dispatcher) {
        val repository = Repository(AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("7.2")),
            FxRate(usd, hkd, BigDecimal("7.8"))), 2))
        val vm = SettingsViewModel(repository, SavedStateHandle())
        runCurrent()
        vm.selectBase(hkd)
        assertEquals("7.8", vm.state.value.rows.single().rateInput)
        vm.updateRow(0, rate = "7.9")
        vm.save()
        runCurrent()
        assertTrue(vm.state.value.saved)
        assertEquals(BigDecimal("7.2"), repository.value.rates.single { it.targetCurrency == cny }.rate)
        assertEquals(BigDecimal("7.9"), repository.value.rates.single { it.targetCurrency == hkd }.rate)
    }
    @Test fun draft_restoration_keeps_base_rate_text_and_original_revision() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val repository = Repository()
        val first = SettingsViewModel(repository, saved)
        runCurrent()
        assertNull(first.state.value.baseCurrency)
        first.selectBase(cny)
        first.addRate()
        first.updateRow(0, usd, "7.1234567890120")
        val restored = SettingsViewModel(repository, saved)
        runCurrent()
        assertEquals("7.1234567890120", restored.state.value.rows.single().rateInput)
        restored.save()
        runCurrent()
        assertTrue(restored.state.value.saved)
        assertEquals(1, repository.writes)
    }
    @Test fun invalid_fx_precision_positive_and_duplicates_never_reach_storage() = runTest(dispatcher) {
        val repository = Repository()
        val vm = SettingsViewModel(repository, SavedStateHandle())
        runCurrent()
        vm.selectBase(cny)
        vm.addRate()
        vm.updateRow(0, usd, "7.1234567890123")
        vm.save()
        assertEquals("PRECISION", vm.state.value.error)
        vm.updateRow(0, rate = "0")
        vm.save()
        assertEquals("POSITIVE", vm.state.value.error)
        vm.updateRow(0, rate = "7")
        vm.addRate()
        vm.updateRow(1, usd, "7")
        vm.save()
        assertEquals("DUPLICATE_CURRENCY", vm.state.value.error)
        assertEquals(0, repository.writes)
    }
    @Test fun lost_metadata_receipt_reconciles_numeric_rate_without_a_second_write() = runTest(dispatcher) {
        val repository = Repository().apply { loseReceipt = true }
        val vm = SettingsViewModel(repository, SavedStateHandle())
        runCurrent()
        vm.selectBase(cny)
        vm.addRate()
        vm.updateRow(0, usd, "7.2000")
        vm.save()
        runCurrent()
        assertTrue(vm.state.value.saved)
        assertEquals(1L, vm.state.value.revision)
        assertEquals(1, repository.writes)
    }
}
