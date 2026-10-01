package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class FxRateDraft(val sourceCurrency: Currency, val rateInput: String)

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val rows: List<FxRateDraft> = emptyList(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val error: ErrorCode? = null,
    val loadFailed: Boolean = false,
    val saved: Boolean = false
)

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val handle: SavedStateHandle
) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()

    init {
        reload()
    }

    fun reload() = viewModelScope.launch {
        try {
            val persisted = repository.observeSettings().first()
            val base = handle.get<String>("base")?.let(Currency::of) ?: persisted.baseCurrency
            val codes = handle.get<ArrayList<String>>("sources")
            val rows = codes?.map { FxRateDraft(Currency.of(it), handle["rate-$it"] ?: "") }
                ?: persisted.rates.filter { it.targetCurrency == base }
                    .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }
            mutable.value = SettingsUiState(persisted.copy(baseCurrency = base), rows, loaded = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = state.value.copy(loadFailed = true)
        }
    }

    private fun change(value: SettingsUiState) {
        mutable.value = value.copy(error = null, saved = false)
        handle["base"] = value.settings.baseCurrency?.code
        handle["sources"] = ArrayList(value.rows.map { it.sourceCurrency.code })
        value.rows.forEach { handle["rate-${it.sourceCurrency.code}"] = it.rateInput }
    }

    fun selectBase(currency: Currency) {
        if (state.value.busy) return
        val persisted = state.value.settings
        change(state.value.copy(settings = persisted.copy(baseCurrency = currency),
            rows = persisted.rates.filter { it.targetCurrency == currency }
                .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }))
    }

    fun addRate() {
        val current = state.value
        if (current.busy || current.settings.baseCurrency == null) return
        val source = Currency.supported.firstOrNull { currency ->
            currency != current.settings.baseCurrency && current.rows.none { it.sourceCurrency == currency }
        } ?: return
        change(current.copy(rows = current.rows + FxRateDraft(source, "1")))
    }

    fun updateRow(index: Int, source: Currency? = null, rate: String? = null) {
        if (state.value.busy) return
        change(state.value.copy(rows = state.value.rows.mapIndexed { rowIndex, row ->
            if (rowIndex != index) row else row.copy(sourceCurrency = source ?: row.sourceCurrency,
                rateInput = rate ?: if (source == null) row.rateInput else state.value.settings.rates
                    .firstOrNull { it.sourceCurrency == source && it.targetCurrency == state.value.settings.baseCurrency }
                    ?.rate?.toPlainString() ?: "1")
        }))
    }

    fun removeRate(index: Int) {
        if (!state.value.busy) change(state.value.copy(rows = state.value.rows.filterIndexed { i, _ -> i != index }))
    }

    fun saveRates() {
        val current = state.value
        if (current.busy) return
        val next = try {
            val base = current.settings.baseCurrency ?: throw DomainException(ErrorCode.CURRENCY)
            if (current.rows.map { it.sourceCurrency.code }.distinct().size != current.rows.size) {
                throw DomainException(ErrorCode.DUPLICATE_CURRENCY)
            }
            val rates = current.rows.map { row ->
                if (!Regex("[0-9]+(?:\\.[0-9]+)?").matches(row.rateInput) || row.rateInput.length > 64) {
                    throw DomainException(ErrorCode.FORMAT)
                }
                val value = BigDecimal(row.rateInput)
                if (value.signum() <= 0) throw DomainException(ErrorCode.POSITIVE)
                if (value.stripTrailingZeros().scale() > 12) throw DomainException(ErrorCode.PRECISION)
                if (value.precision() > 40) throw DomainException(ErrorCode.OVERFLOW)
                FxRate(row.sourceCurrency, base, value.stripTrailingZeros())
            }
            current.settings.copy(rates = current.settings.rates.filter { it.targetCurrency != base } + rates)
        } catch (error: DomainException) {
            mutable.value = current.copy(error = error.code)
            return
        }
        persist(next)
    }

    fun saveLanguage(language: AppLanguage) = persist(state.value.settings.copy(language = language))

    fun saveGainLossColors(scheme: GainLossColorScheme) =
        persist(state.value.settings.copy(gainLossColors = scheme))

    private fun persist(next: AppSettings) {
        val current = state.value
        if (current.busy || !current.loaded) return
        mutable.value = current.copy(busy = true, error = null, saved = false)
        viewModelScope.launch {
            try {
                repository.saveSettings(next, current.settings.revision)
                handle.remove<String>("base")
                handle.remove<ArrayList<String>>("sources")
                val stored = repository.observeSettings().first { it.revision > current.settings.revision }
                mutable.value = SettingsUiState(stored,
                    stored.rates.filter { it.targetCurrency == stored.baseCurrency }
                        .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) },
                    loaded = true, saved = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DomainException) {
                mutable.value = current.copy(error = error.code)
            } catch (_: Exception) {
                val stored = runCatching { repository.observeSettings().first() }.getOrNull()
                if (stored != null && stored.matches(next)) {
                    mutable.value = SettingsUiState(stored,
                        stored.rates.filter { it.targetCurrency == stored.baseCurrency }
                            .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) },
                        loaded = true, saved = true)
                } else {
                    mutable.value = current.copy(error = ErrorCode.STALE_RECORD)
                }
            }
        }
    }

    private fun AppSettings.matches(other: AppSettings): Boolean =
        baseCurrency == other.baseCurrency && language == other.language &&
            gainLossColors == other.gainLossColors && rates.size == other.rates.size &&
            rates.all { rate -> other.rates.any { candidate ->
                candidate.sourceCurrency == rate.sourceCurrency &&
                    candidate.targetCurrency == rate.targetCurrency &&
                    candidate.rate.compareTo(rate.rate) == 0
            } }
}
