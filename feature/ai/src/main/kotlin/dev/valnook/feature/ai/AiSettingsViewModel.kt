package dev.valnook.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.ai.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The connection as typed; [apiKey] is a new key to save, empty keeps the saved one. */
data class AiConnectionForm(
    val baseUrl: String,
    val apiKey: String = "",
    val removeKey: Boolean = false,
    val model: String,
    val extraParams: String,
    val thinkingOffParams: String,
    val thinkingOnParams: String,
) {
    /** One connection, filled in by the user; there are no presets. */
    fun connection() = AiConnection(null, AiConnection.normalizeBaseUrl(baseUrl), model.trim(), extraParams.trim(),
        thinkingOffParams.trim(), thinkingOnParams.trim())

    companion object {
        fun of(connection: AiConnection) = AiConnectionForm(connection.baseUrl, model = connection.model,
            extraParams = connection.extraParams, thinkingOffParams = connection.thinkingOffParams,
            thinkingOnParams = connection.thinkingOnParams)

        /** Empty, or one JSON object of a size a request can carry: the rule the data layer applies. */
        fun validParams(text: String): Boolean = text.isBlank() || (text.length <= AiRules.PARAMS_MAX &&
            runCatching { Json.parseToJsonElement(text) is JsonObject }.getOrDefault(false))
    }
}

data class AiPersonalForm(val myRules: String, val userNickname: String, val aiNickname: String)

data class AiSettingsUi(
    val loaded: Boolean = false,
    val settings: AiSettings = AiSettings(),
    val keySaved: Boolean = false,
    val report: AiCheckReport? = null,
    val choices: AiChoices = AiChoices(),
    val form: AiConnectionForm = AiConnectionForm.of(AiSettings().connection),
    val personal: AiPersonalForm = AiPersonalForm("", "", ""),
    val models: List<String>? = null,
    val modelsLoading: Boolean = false,
    val modelsProblem: AiProblem? = null,
    /** The check running now; null when none runs. */
    val checking: AiCheckItem? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    /** This month's AI requests; null without conversations (demo, tests). */
    val usage: AiUsageSummary? = null,
) {
    /** The master switch can be turned on: the required checks of the saved connection passed. */
    val canEnable: Boolean get() = report?.usable == true
    val imagePassed: Boolean get() = report?.of(AiCheckItem.IMAGE)?.state == AiCheckState.PASSED
    val thinkingPassed: Boolean get() = report?.of(AiCheckItem.THINKING)?.state == AiCheckState.PASSED &&
        settings.connection.thinkingOnParams.isNotBlank()

    val connectionDirty: Boolean get() = form.connection().let { typed ->
        val saved = settings.connection
        typed.baseUrl != saved.baseUrl || typed.model != saved.model || typed.extraParams != saved.extraParams ||
            typed.thinkingOffParams != saved.thinkingOffParams || typed.thinkingOnParams != saved.thinkingOnParams
    } || form.apiKey.isNotEmpty() || form.removeKey

    val personalDirty: Boolean get() = personal != AiPersonalForm(settings.myRules, settings.userNickname, settings.aiNickname)

    val dirty: Boolean get() = connectionDirty || personalDirty

    val urlValid: Boolean get() = AiConnection.validBaseUrl(AiConnection.normalizeBaseUrl(form.baseUrl))

    val connectionValid: Boolean get() = urlValid && form.model.isNotBlank() && form.model.length <= AiRules.MODEL_MAX &&
        form.apiKey.length <= AiRules.KEY_MAX && form.apiKey.none { it.isWhitespace() } &&
        listOf(form.extraParams, form.thinkingOffParams, form.thinkingOnParams).all(AiConnectionForm::validParams)

    val personalValid: Boolean get() = personal.myRules.length <= AiRules.MY_RULES_MAX &&
        personal.userNickname.trim().length <= AiRules.NICKNAME_MAX && personal.aiNickname.trim().length <= AiRules.NICKNAME_MAX

    val busy: Boolean get() = saving || checking != null
}

/** Settings → AI. The connection and personal sections are drafts saved with their buttons; defaults save at once. */
class AiSettingsViewModel(private val service: AiService, val avatars: AiAvatars,
    private val assistant: AiAssistant? = null) : ViewModel() {
    private val mutable = MutableStateFlow(AiSettingsUi())
    val state: StateFlow<AiSettingsUi> = mutable

    init {
        assistant?.let { chat -> viewModelScope.launch { chat.observeUsage().collect { usage -> mutable.update { it.copy(usage = usage) } } } }
        viewModelScope.launch {
            combine(service.observeSettings(), service.observeKeySaved(), service.observeChecks(), service.observeChoices()) {
                settings, keySaved, report, choices -> listOf(settings, keySaved, report, choices)
            }.collect { (settings, keySaved, report, choices) ->
                settings as AiSettings
                mutable.update { ui ->
                    // Drafts follow the saved values until the user starts changing them.
                    val form = if (!ui.loaded || !ui.connectionDirty) AiConnectionForm.of(settings.connection) else ui.form
                    val personal = if (!ui.loaded || !ui.personalDirty) AiPersonalForm(settings.myRules, settings.userNickname,
                        settings.aiNickname) else ui.personal
                    ui.copy(loaded = true, settings = settings, keySaved = keySaved as Boolean, report = report as AiCheckReport?,
                        choices = choices as AiChoices, form = form, personal = personal)
                }
            }
        }
    }

    /** Switching on needs the required checks passed; the page asks the first time before calling this. */
    fun setEnabled(enabled: Boolean) {
        if (enabled && !state.value.canEnable) return
        viewModelScope.launch { runCatching { service.setEnabled(enabled) } }
    }

    /** One feature switch changed; those whose check did not pass stay off. */
    fun setFeatures(change: (AiSettings) -> AiSettings) {
        val ui = state.value
        val next = change(ui.settings)
        viewModelScope.launch {
            runCatching {
                service.saveFeatures(next.billChat, next.shareText, next.shareImage && ui.imagePassed,
                    next.thinking && ui.thinkingPassed, next.chat, next.sendHabits)
            }
        }
    }

    fun setKeepImages(keep: Boolean) {
        viewModelScope.launch { runCatching { service.saveKeepImages(keep) } }
    }

    fun clearConversations() {
        val chat = assistant ?: return
        viewModelScope.launch { runCatching { chat.clearConversations() } }
    }

    fun editConnection(change: (AiConnectionForm) -> AiConnectionForm) = mutable.update { it.copy(form = change(it.form), saveFailed = false) }

    /** Whether the quick start's example would replace an address, model or parameters typed in now. */
    fun exampleReplaces(): Boolean = AiGuideExample.replaces(mutable.value.form)

    /** The quick start's example in the form, the key left as it is; saved only when the user saves. */
    fun fillExample() = editConnection(AiGuideExample::fill)

    fun editPersonal(change: (AiPersonalForm) -> AiPersonalForm) = mutable.update { it.copy(personal = change(it.personal), saveFailed = false) }

    fun fetchModels() {
        val ui = state.value
        if (ui.modelsLoading || !ui.urlValid) return
        mutable.update { it.copy(modelsLoading = true, modelsProblem = null) }
        viewModelScope.launch {
            val result = try { Result.success(service.fetchModels(ui.form.connection(), ui.form.apiKey.ifEmpty { null })) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: AiException) { Result.failure(error) }
                catch (error: Exception) { Result.failure(AiException(AiProblem.BAD_RESPONSE)) }
            mutable.update { current ->
                current.copy(modelsLoading = false, models = result.getOrNull() ?: current.models,
                    modelsProblem = (result.exceptionOrNull() as? AiException)?.problem)
            }
        }
    }

    fun saveConnection(then: (suspend () -> Unit)? = null) {
        val ui = state.value
        if (ui.busy || !ui.connectionValid) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = try {
                service.saveConnection(ui.form.connection(), when {
                    ui.form.apiKey.isNotEmpty() -> ui.form.apiKey
                    ui.form.removeKey -> ""
                    else -> null
                })
                true
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            mutable.update { current ->
                // The typed key leaves the draft once saved; the form shows what was saved, tidied as saving tidies it.
                if (saved) current.copy(saving = false, form = AiConnectionForm.of(ui.form.connection()))
                else current.copy(saving = false, saveFailed = true)
            }
            if (saved) then?.invoke()
        }
    }

    /** Saves a changed connection first, then runs every check against it. */
    fun check() {
        val ui = state.value
        if (ui.busy) return
        if (ui.connectionDirty) { if (ui.connectionValid) saveConnection { runChecks() }; return }
        viewModelScope.launch { runChecks() }
    }

    private suspend fun runChecks() {
        mutable.update { it.copy(checking = AiCheckItem.CONNECT) }
        try { service.check { item -> mutable.update { it.copy(checking = item) } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { }
        finally { mutable.update { it.copy(checking = null) } }
    }

    fun saveDefaults(timeMinutes: Int = state.value.settings.defaultTimeMinutes,
        cashAccountId: Long? = state.value.settings.defaultCashAccountId,
        expenseCategoryId: Long? = state.value.settings.defaultExpenseCategoryId,
        incomeCategoryId: Long? = state.value.settings.defaultIncomeCategoryId) {
        viewModelScope.launch {
            runCatching { service.saveDefaults(timeMinutes, cashAccountId, expenseCategoryId, incomeCategoryId) }
                .onFailure { if (it is CancellationException) throw it; mutable.update { ui -> ui.copy(saveFailed = true) } }
        }
    }

    fun savePersonal() {
        val ui = state.value
        if (!ui.personalValid || ui.saving) return
        mutable.update { it.copy(saving = true, saveFailed = false) }
        viewModelScope.launch {
            val saved = runCatching { service.savePersonal(ui.personal.myRules, ui.personal.userNickname, ui.personal.aiNickname) }
                .onFailure { if (it is CancellationException) throw it }.isSuccess
            mutable.update { it.copy(saving = false, saveFailed = !saved) }
        }
    }

    fun setAvatar(who: AiAvatar, bytes: ByteArray?, failed: () -> Unit) {
        viewModelScope.launch { if (bytes == null || !avatars.set(who, bytes)) failed() }
    }

    fun clearAvatar(who: AiAvatar) { viewModelScope.launch { avatars.clear(who) } }
}

/** The settings entry's line: checked and usable with the host, failed, or not checked yet. */
fun aiSummary(service: AiService): Flow<Pair<AiCheckReport?, String>> =
    combine(service.observeChecks(), service.observeSettings()) { report, settings -> report to settings.connection.host }
