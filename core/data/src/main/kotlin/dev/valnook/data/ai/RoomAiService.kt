package dev.valnook.data.ai

import dev.valnook.data.database.AiSettingsEntity
import dev.valnook.data.database.BillAccountRow
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.toModel
import dev.valnook.domain.ai.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.LocalDateTime

/**
 * The AI settings of one database with the device's key and check report. [english] says which language the user
 * reads, for category names and the language replies come in.
 */
class RoomAiService(
    private val db: ValnookDatabase,
    private val keys: AiKeyStore,
    private val checks: AiCheckStore,
    private val clock: Clock,
    private val english: () -> Boolean,
    private val client: AiClient = AiClient(),
    private val checker: AiChecker = AiChecker(client, AiCheckImage::jpeg),
) : AiService {

    override fun observeSettings(): Flow<AiSettings> = db.ai().observeSettings().map { it?.toModel() ?: AiSettings() }

    override fun observeChecks(): Flow<AiCheckReport?> = combine(observeSettings(), checks.report) { settings, report ->
        report?.takeIf { it.fingerprint == settings.connection.fingerprint }
    }

    override fun observeKeySaved(): Flow<Boolean> = keys.saved

    override fun observeChoices(): Flow<AiChoices> = combine(db.bills().observeAccountRows(), db.bills().observeCategories()) { accounts, categories ->
        AiChoices(accounts.map { it.choice() }, categories.map { it.toModel() })
    }

    override fun observeFeatures(): Flow<AiFeatures> =
        combine(observeSettings(), observeChecks()) { settings, report -> AiFeatures.of(settings, report) }.distinctUntilChanged()

    override suspend fun setEnabled(enabled: Boolean) {
        if (enabled) require(observeChecks().first()?.usable == true)
        update { it.copy(enabled = enabled) }
    }

    override suspend fun saveFeatures(billChat: Boolean, shareText: Boolean, shareImage: Boolean, thinking: Boolean,
        chat: Boolean, sendHabits: Boolean) {
        update { it.copy(bill_chat = billChat, share_text = shareText, share_image = shareImage, thinking = thinking,
            chat = chat, send_habits = sendHabits) }
    }

    override suspend fun saveConnection(connection: AiConnection, apiKey: String?) {
        val clean = connection.copy(baseUrl = AiConnection.normalizeBaseUrl(connection.baseUrl), model = connection.model.trim(),
            extraParams = connection.extraParams.trim(), thinkingOffParams = connection.thinkingOffParams.trim(),
            thinkingOnParams = connection.thinkingOnParams.trim())
        require(clean.baseUrl.isEmpty() || AiConnection.validBaseUrl(clean.baseUrl))
        require(clean.model.length <= AiRules.MODEL_MAX)
        require(listOf(clean.extraParams, clean.thinkingOffParams, clean.thinkingOnParams).all(AiJson::validParams))
        require(apiKey == null || (apiKey.length <= AiRules.KEY_MAX && apiKey.none { it.isWhitespace() }))
        val old = observeSettings().first()
        update { it.copy(preset_id = clean.presetId, base_url = clean.baseUrl, model = clean.model,
            extra_params = clean.extraParams, thinking_off_params = clean.thinkingOffParams, thinking_on_params = clean.thinkingOnParams) }
        // A new key may reach another account, so the checks start over with it too.
        if (apiKey != null) withContext(Dispatchers.IO) {
            if (apiKey.isEmpty()) keys.clear() else keys.write(apiKey)
            checks.clear()
        }
        if (old.connection.fingerprint != clean.fingerprint) checks.clear()
    }

    override suspend fun saveDefaults(timeMinutes: Int, cashAccountId: Long?, expenseCategoryId: Long?, incomeCategoryId: Long?) {
        require(timeMinutes in 0 until 24 * 60)
        update { it.copy(default_time_minutes = timeMinutes, default_cash_account_id = cashAccountId,
            default_expense_category_id = expenseCategoryId, default_income_category_id = incomeCategoryId) }
    }

    override suspend fun savePersonal(myRules: String, userNickname: String, aiNickname: String) {
        require(myRules.length <= AiRules.MY_RULES_MAX)
        require(userNickname.trim().length <= AiRules.NICKNAME_MAX && aiNickname.trim().length <= AiRules.NICKNAME_MAX)
        update { it.copy(my_rules = myRules, user_nickname = userNickname.trim(), ai_nickname = aiNickname.trim()) }
    }

    override suspend fun fetchModels(connection: AiConnection, apiKey: String?): List<String> {
        val clean = connection.copy(baseUrl = AiConnection.normalizeBaseUrl(connection.baseUrl))
        if (!AiConnection.validBaseUrl(clean.baseUrl)) throw AiException(AiProblem.NOT_FOUND)
        return client.models(clean, apiKey ?: savedKey())
    }

    override suspend fun check(progress: (AiCheckItem) -> Unit): AiCheckReport {
        val connection = observeSettings().first().connection
        val results = if (!connection.usable) mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.FAILED, AiProblem.NOT_FOUND))
            else checker.run(connection, savedKey(), progress)
        val report = AiCheckReport(connection.fingerprint, clock.millis(), results)
        // Saved only if the connection is still the one checked; a change meanwhile voided it.
        if (observeSettings().first().connection.fingerprint == connection.fingerprint) checks.save(report)
        return report
    }

    override suspend fun promptPreview(): String {
        val settings = observeSettings().first()
        val choices = db.withAccountUse(observeChoices().first())
        val base = db.overview().settings()?.base_currency
        val habits = if (settings.sendHabits) db.bills().recentHabits(AiRules.HABITS).map { AiHabit(it.note, it.category_id) }
            else emptyList()
        val system = AiPrompt.system(settings, choices.categories, choices.accounts,
            LocalDateTime.now(clock), base, english(), habits)
        val placeholder = if (english()) "(what you type or share)" else "（你输入或分享的内容）"
        val body = AiPrompt.billRequest(system, listOf(AiPrompt.message("user", placeholder)), settings.thinking)
        return AiJson.pretty.encodeToString(kotlinx.serialization.json.JsonObject.serializer(),
            client.request(settings.connection, body, settings.thinking))
    }

    private suspend fun savedKey(): String? = withContext(Dispatchers.IO) {
        try { keys.read() } catch (_: Exception) { throw AiException(AiProblem.NO_KEY_STORAGE) }
    }

    private suspend fun update(change: (AiSettingsEntity) -> AiSettingsEntity) {
        val now = clock.millis()
        val current = db.ai().settings() ?: AiSettings().toEntity()
        db.ai().save(change(current).copy(id = 1, revision = current.revision + 1, updated_at_ms = now))
    }

    private fun BillAccountRow.choice() =
        AiAccountChoice(id, if (account_name == name || name.isBlank()) account_name else "$account_name · $name", currency_code,
            is_favorite, credit = is_credit, hidden = !show_on_accounts_page)
}

/** The choices with when each account was last used, read for a request only: it changes with every bill. */
internal suspend fun ValnookDatabase.withAccountUse(choices: AiChoices): AiChoices {
    val used = bills().accountLastUse().associate { it.id to it.last_ms }
    return choices.copy(accounts = choices.accounts.map { it.copy(lastUsedMs = used[it.id]) })
}

internal fun AiSettingsEntity.toModel() = AiSettings(
    AiConnection(preset_id, base_url, model, extra_params, thinking_off_params, thinking_on_params),
    enabled, bill_chat, share_text, share_image, query_enabled, thinking, chat, send_habits, default_time_minutes,
    default_cash_account_id, default_expense_category_id, default_income_category_id, my_rules, user_nickname, ai_nickname)

internal fun AiSettings.toEntity() = AiSettingsEntity(1, connection.presetId, connection.baseUrl, connection.model,
    connection.extraParams, connection.thinkingOffParams, connection.thinkingOnParams, enabled, billChat, shareText,
    shareImage, query, thinking, chat, sendHabits, defaultTimeMinutes, defaultCashAccountId, defaultExpenseCategoryId,
    defaultIncomeCategoryId, myRules, userNickname, aiNickname, 0, 0)
