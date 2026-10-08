package dev.valnook.app.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.settings.FxRateDraft
import dev.valnook.feature.settings.validateFxRates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OnboardingState(
    val draft: OnboardingDraft = OnboardingDraft(),
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val error: ErrorCode? = null,
    val loadFailed: Boolean = false
)

class OnboardingViewModel(
    private val store: OnboardingStorage,
    private val settings: SettingsRepository,
    private val writer: SettingsWriter
) : ViewModel() {
    private val mutable = MutableStateFlow(OnboardingState())
    val state = mutable.asStateFlow()
    private val writes = Mutex()

    init { reload() }

    fun reload() = viewModelScope.launch {
        writes.withLock {
            try {
                val draft = withContext(Dispatchers.IO) { store.read() }
                mutable.value = OnboardingState(draft, loaded = true, language = settings.observeSettings().first().language)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutable.value = mutable.value.copy(loadFailed = true) }
        }
    }

    private fun action(block: suspend () -> Unit) {
        if (!state.value.loaded || state.value.busy) return
        viewModelScope.launch {
            writes.withLock {
                if (state.value.draft.step in listOf(OnboardingStep.COMPLETE, OnboardingStep.LEGACY)) return@withLock
                try {
                    mutable.value = state.value.copy(error = null)
                    block()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: DomainException) { mutable.value = state.value.copy(error = error.code)
                } catch (_: Exception) { mutable.value = state.value.copy(error = ErrorCode.STALE_RECORD)
                } finally { mutable.value = state.value.copy(busy = false) }
            }
        }
    }

    private suspend fun save(draft: OnboardingDraft) {
        withContext(Dispatchers.IO) { store.save(draft) }
        mutable.value = state.value.copy(draft = draft)
    }

    fun introSeen() = action { save(state.value.draft.copy(introSeen = true)) }
    fun accept(value: Boolean) = action { save(state.value.draft.copy(accepted = value)) }
    fun start() = action {
        if (state.value.draft.accepted) save(state.value.draft.copy(step = OnboardingStep.FINANCE))
    }
    fun back() = action { save(state.value.draft.copy(step = OnboardingStep.WELCOME)) }
    fun base(value: Currency) = action { save(state.value.draft.copy(base = value, rates = emptyList())) }
    fun addRate() = action {
        val draft = state.value.draft
        if (draft.base == null) return@action
        val next = Currency.supported.firstOrNull { it != draft.base && draft.rates.none { row -> row.sourceCurrency == it } }
            ?: return@action
        save(draft.copy(rates = draft.rates + FxRateDraft(next, "1")))
    }
    fun updateRate(index: Int, currency: Currency?, value: String?) = action {
        val draft = state.value.draft
        save(draft.copy(rates = draft.rates.mapIndexed { i, row ->
            if (i != index) row else FxRateDraft(currency ?: row.sourceCurrency, value ?: "1")
        }))
    }
    fun removeRate(index: Int) = action {
        save(state.value.draft.copy(rates = state.value.draft.rates.filterIndexed { i, _ -> i != index }))
    }
    fun language(language: AppLanguage) = action {
        mutable.value = state.value.copy(busy = true)
        save(state.value.draft.copy(introSeen = true))
        val current = settings.observeSettings().first()
        if (current.language != language) writer.applyChange(SaveLanguage(current.revision, language))
        mutable.value = state.value.copy(language = language)
    }
    fun finish() = action {
        val draft = state.value.draft
        if (!draft.accepted || draft.step != OnboardingStep.FINANCE) return@action
        val base = draft.base ?: throw DomainException(ErrorCode.CURRENCY)
        val rates = validateFxRates(base, draft.rates)
        mutable.value = state.value.copy(busy = true)
        val current = settings.observeSettings().first()
        // Idempotent after process death between committing finances and the local completion marker.
        if (current.baseCurrency != base || current.rates != rates) {
            writer.applyChange(SaveFinancialSettings(current.revision, base, rates))
        }
        save(draft.copy(step = OnboardingStep.READY))
    }
    fun complete() = action {
        if (state.value.draft.step != OnboardingStep.READY) return@action
        mutable.value = state.value.copy(busy = true)
        save(state.value.draft.copy(step = OnboardingStep.COMPLETE))
    }
}
