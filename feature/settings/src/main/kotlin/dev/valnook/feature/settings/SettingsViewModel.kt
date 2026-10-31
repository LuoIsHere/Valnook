package dev.valnook.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.fx.FxRateSync
import dev.valnook.domain.fx.FxSyncStatus
import dev.valnook.domain.fx.UnavailableFxRateSync
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class FxRateDraft(val sourceCurrency: Currency, val rateInput: String)

/** What turning automatic rates on is waiting for. */
enum class AutoRatesPrompt { DISCARD_CHANGES, NETWORK_NOTICE }

data class SettingsUiState(
    val savedSettings: AppSettings = AppSettings(),
    val settings: AppSettings = AppSettings(),
    val rows: List<FxRateDraft> = emptyList(),
    val baselineRevision: Long = 0,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val dirty: Boolean = false,
    val error: ErrorCode? = null,
    val loadFailed: Boolean = false,
    val saved: Boolean = false,
    val fx: FxSyncStatus = FxSyncStatus(),
    val autoPrompt: AutoRatesPrompt? = null
) {
    val automatic: Boolean get() = fx.available && fx.autoEnabled
    /** In automatic mode the list shows the stored current rates, read only. */
    val shownRows: List<FxRateDraft> get() = if (!automatic) rows else savedSettings.rates
        .filter { it.targetCurrency == savedSettings.baseCurrency }
        .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }
    val shownBase: Currency? get() = if (automatic) savedSettings.baseCurrency else settings.baseCurrency
}

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val writer: SettingsWriter,
    private val handle: SavedStateHandle,
    private val fxSync: FxRateSync = UnavailableFxRateSync
) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state = mutable.asStateFlow()
    private var observation: Job? = null

    init {
        reload()
        viewModelScope.launch {
            try {
                fxSync.observeStatus().collect { status ->
                    val switchedOff = state.value.fx.autoEnabled && !status.autoEnabled
                    mutable.value = state.value.copy(fx = status)
                    // Turning automatic rates off leaves the last automatic values to edit.
                    if (switchedOff && state.value.loaded) discardAndReload()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Without a status the switch stays off and manual editing works as before.
            }
        }
    }

    fun reload() {
        observation?.cancel()
        observation = viewModelScope.launch {
            try {
                repository.observeSettings().collect { persisted ->
                    val current = state.value
                    if (!current.loaded) initialize(persisted) else {
                        mutable.value = current.copy(savedSettings = persisted, loadFailed = false)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = state.value.copy(loadFailed = true)
            }
        }
    }

    private fun initialize(persisted: AppSettings) {
        val restored = handle.get<Boolean>(KEY_INITIALIZED) == true
        val base = if (restored) handle.get<String>(KEY_BASE)?.let(Currency::of) else persisted.baseCurrency
        val codes = if (restored) handle.get<ArrayList<String>>(KEY_SOURCES) else null
        val rows = codes?.map { FxRateDraft(Currency.of(it), handle["rate-$it"] ?: "") }
            ?: persisted.rates.filter { it.targetCurrency == base }
                .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }
        val draft = persisted.copy(
            baseCurrency = base,
            language = handle.get<String>(KEY_LANGUAGE)?.let(AppLanguage::valueOf) ?: persisted.language,
            gainLossColors = handle.get<String>(KEY_COLORS)?.let(GainLossColorScheme::valueOf)
                ?: persisted.gainLossColors,
            revision = handle.get<Long>(KEY_BASELINE) ?: persisted.revision
        )
        mutable.value = SettingsUiState(
            savedSettings = persisted,
            settings = draft,
            rows = rows,
            baselineRevision = draft.revision,
            loaded = true,
            dirty = restored && handle.get<Boolean>(KEY_DIRTY) == true,
            fx = state.value.fx
        )
        persistDraft(mutable.value)
    }

    private fun change(value: SettingsUiState) {
        val next = value.copy(error = null, saved = false, dirty = true)
        mutable.value = next
        persistDraft(next)
    }

    private fun persistDraft(value: SettingsUiState) {
        handle[KEY_INITIALIZED] = true
        handle[KEY_BASE] = value.settings.baseCurrency?.code
        handle[KEY_LANGUAGE] = value.settings.language.name
        handle[KEY_COLORS] = value.settings.gainLossColors.name
        handle[KEY_BASELINE] = value.baselineRevision
        handle[KEY_DIRTY] = value.dirty
        handle[KEY_SOURCES] = ArrayList(value.rows.map { it.sourceCurrency.code })
        value.rows.forEach { handle["rate-${it.sourceCurrency.code}"] = it.rateInput }
    }

    fun selectBase(currency: Currency) {
        if (state.value.busy) return
        val current = state.value
        // In automatic mode a new base is saved at once and its rates fetched; the stored revision moves with each sync.
        if (current.automatic) {
            persist(SaveFinancialSettings(current.savedSettings.revision, currency, current.savedSettings.rates))
            return
        }
        val draft = state.value.settings
        change(state.value.copy(settings = draft.copy(baseCurrency = currency),
            rows = draft.rates.filter { it.targetCurrency == currency }
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

    fun selectLanguage(language: AppLanguage) {
        if (!state.value.busy) change(state.value.copy(settings = state.value.settings.copy(language = language)))
    }

    fun selectGainLossColors(colors: GainLossColorScheme) {
        if (!state.value.busy) change(state.value.copy(settings = state.value.settings.copy(gainLossColors = colors)))
    }

    fun discardAndReload() {
        val savedSettings = state.value.savedSettings
        val rows = savedSettings.rates.filter { it.targetCurrency == savedSettings.baseCurrency }
            .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }
        val next = SettingsUiState(savedSettings, savedSettings, rows, savedSettings.revision, loaded = true,
            fx = state.value.fx)
        mutable.value = next
        persistDraft(next)
    }

    /** Turning on asks to drop unsaved edits, then shows the network notice unless dismissed; turning off is immediate. */
    fun requestAutomatic(on: Boolean) {
        val current = state.value
        if (current.busy || !current.fx.available || on == current.fx.autoEnabled) return
        if (!on) {
            fx { fxSync.setAutoEnabled(false) }
            return
        }
        when {
            current.dirty -> mutable.value = current.copy(autoPrompt = AutoRatesPrompt.DISCARD_CHANGES)
            !current.fx.noticeDismissed -> mutable.value = current.copy(autoPrompt = AutoRatesPrompt.NETWORK_NOTICE)
            else -> fx { fxSync.setAutoEnabled(true) }
        }
    }

    fun discardForAutomatic() {
        discardAndReload()
        if (!state.value.fx.noticeDismissed) mutable.value = state.value.copy(autoPrompt = AutoRatesPrompt.NETWORK_NOTICE)
        else fx { fxSync.setAutoEnabled(true) }
    }

    fun acceptNetworkNotice(dontShowAgain: Boolean) {
        mutable.value = state.value.copy(autoPrompt = null)
        fx {
            if (dontShowAgain) fxSync.dismissNotice()
            fxSync.setAutoEnabled(true)
        }
    }

    fun cancelAutomatic() {
        mutable.value = state.value.copy(autoPrompt = null)
    }

    fun refreshRates() = fx { fxSync.refreshNow() }

    private fun fx(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DomainException) {
                mutable.value = state.value.copy(error = error.code)
            } catch (_: Exception) {
                mutable.value = state.value.copy(error = ErrorCode.STALE_RECORD)
            }
        }
    }

    fun saveRates() {
        val current = state.value
        if (current.busy) return
        val change = try {
            val base = current.settings.baseCurrency ?: throw DomainException(ErrorCode.CURRENCY)
            val rates = validateFxRates(base, current.rows)
            SaveFinancialSettings(current.baselineRevision, base,
                current.settings.rates.filter { it.targetCurrency != base } + rates)
        } catch (error: DomainException) {
            mutable.value = current.copy(error = error.code)
            return
        }
        persist(change)
    }

    fun saveLanguage() = persist(SaveLanguage(state.value.baselineRevision, state.value.settings.language))

    fun saveGainLossColors() = persist(
        SaveGainLossColors(state.value.baselineRevision, state.value.settings.gainLossColors))

    private fun persist(change: SettingsChange) {
        val current = state.value
        if (current.busy || !current.loaded) return
        mutable.value = current.copy(busy = true, error = null, saved = false)
        viewModelScope.launch {
            try {
                val stored = writer.applyChange(change)
                val rows = stored.rates.filter { it.targetCurrency == stored.baseCurrency }
                    .map { FxRateDraft(it.sourceCurrency, it.rate.toPlainString()) }
                val next = SettingsUiState(stored, stored, rows, stored.revision, loaded = true, saved = true,
                    fx = state.value.fx)
                mutable.value = next
                persistDraft(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DomainException) {
                mutable.value = current.copy(savedSettings = state.value.savedSettings,
                    busy = false, error = error.code)
            } catch (_: Exception) {
                mutable.value = current.copy(savedSettings = state.value.savedSettings,
                    busy = false, error = ErrorCode.STALE_RECORD)
            }
        }
    }

    private companion object {
        const val KEY_INITIALIZED = "draftInitialized"
        const val KEY_BASE = "base"
        const val KEY_LANGUAGE = "language"
        const val KEY_COLORS = "colors"
        const val KEY_BASELINE = "baselineRevision"
        const val KEY_DIRTY = "draftDirty"
        const val KEY_SOURCES = "sources"
    }
}
