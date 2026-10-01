package dev.valnook.feature.settings

import androidx.lifecycle.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.math.BigDecimal

data class FxRateDraft(val sourceCurrency: Currency, val rateInput: String)
data class FxSettingsUiState(val baseCurrency: Currency? = null, val rows: List<FxRateDraft> = emptyList(),
    val existingRates: List<FxRate> = emptyList(), val revision: Long = 0, val loaded: Boolean = false,
    val busy: Boolean = false, val error: String? = null, val saved: Boolean = false)
class SettingsViewModel(private val repository: SettingsRepository, private val handle: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(FxSettingsUiState())
    val state = mutable.asStateFlow()
    init { reload() }
    fun reload() = viewModelScope.launch {
        try {
            val persisted = repository.observeSettings().first()
            val base = handle.get<String>("base")?.let(Currency::of) ?: persisted.baseCurrency
            val codes = handle.get<ArrayList<String>>("sources")
            val rows = codes?.map { FxRateDraft(Currency.of(it), handle["rate-$it"] ?: "") } ?:
                persisted.rates.filter { it.targetCurrency == base }.map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }
            mutable.value = FxSettingsUiState(base, rows, persisted.rates,
                handle.get<Long>("revision") ?: persisted.revision, true)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = state.value.copy(error = "读取失败，请重试") }
    }
    private fun change(value: FxSettingsUiState) {
        mutable.value = value.copy(error = null, saved = false)
        handle["base"] = value.baseCurrency?.code
        handle["revision"] = value.revision
        handle["sources"] = ArrayList(value.rows.map { it.sourceCurrency.code })
        value.rows.forEach { handle["rate-${it.sourceCurrency.code}"] = it.rateInput }
    }
    fun selectBase(currency: Currency) {
        if (state.value.busy) return
        change(state.value.copy(baseCurrency = currency, rows = state.value.existingRates.filter { it.targetCurrency == currency }
            .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }))
    }
    fun addRate() {
        if (state.value.busy || state.value.baseCurrency == null) return
        val source = Currency.supported.firstOrNull { it != state.value.baseCurrency && state.value.rows.none { row -> row.sourceCurrency == it } } ?: return
        change(state.value.copy(rows = state.value.rows + FxRateDraft(source, "")))
    }
    fun updateRow(index: Int, source: Currency? = null, rate: String? = null) {
        if (!state.value.busy) change(state.value.copy(rows = state.value.rows.mapIndexed { i, row ->
            if (i != index) row else row.copy(sourceCurrency = source ?: row.sourceCurrency, rateInput = rate ?: row.rateInput)
        }))
    }
    fun removeRate(index: Int) {
        if (!state.value.busy) change(state.value.copy(rows = state.value.rows.filterIndexed { i, _ -> i != index }))
    }
    fun save() {
        if (state.value.busy) return
        val input = state.value
        val settings = try {
            val base = input.baseCurrency ?: throw DomainException(ErrorCode.CURRENCY)
            if (input.rows.map { it.sourceCurrency.code }.distinct().size != input.rows.size)
                throw DomainException(ErrorCode.DUPLICATE_CURRENCY)
            val rates = input.rows.map { row ->
                if (!Regex("[0-9]+(?:\\.[0-9]+)?").matches(row.rateInput) || row.rateInput.length > 64)
                    throw DomainException(ErrorCode.FORMAT)
                val value = BigDecimal(row.rateInput)
                if (value.signum() <= 0) throw DomainException(ErrorCode.POSITIVE)
                if (value.stripTrailingZeros().scale() > 12) throw DomainException(ErrorCode.PRECISION)
                FxRate(row.sourceCurrency, base, value)
            }
            AppSettings(base, input.existingRates.filter { it.targetCurrency != base } + rates, input.revision)
        } catch (error: DomainException) { mutable.value = input.copy(error = error.code.name)
        return }
        mutable.value = input.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                repository.saveSettings(settings, input.revision)
                val actual = repository.observeSettings().first()
                change(input.copy(existingRates = actual.rates, revision = actual.revision))
                mutable.value = state.value.copy(saved = true, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: DomainException) { mutable.value = input.copy(error = error.code.name) }
            catch (_: Exception) {
                // Metadata writes also reconcile a lost receipt instead of blindly overwriting.
                val actual = try { repository.observeSettings().first() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val expectedPairs = settings.rates.associate { (it.sourceCurrency to it.targetCurrency) to it.rate.stripTrailingZeros() }
                val actualPairs = actual?.rates?.associate { (it.sourceCurrency to it.targetCurrency) to it.rate.stripTrailingZeros() }
                if (actual != null && actual.baseCurrency == settings.baseCurrency && actualPairs == expectedPairs) {
                    change(input.copy(existingRates = actual.rates, revision = actual.revision))
                    mutable.value = state.value.copy(saved = true)
                } else mutable.value = input.copy(error = "保存结果待核对，请重新加载")
            }
        }
    }
}
