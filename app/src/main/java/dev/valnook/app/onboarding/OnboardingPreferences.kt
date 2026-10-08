package dev.valnook.app.onboarding

import android.content.Context
import android.content.SharedPreferences
import dev.valnook.domain.model.Currency
import dev.valnook.feature.settings.FxRateDraft
import org.json.JSONArray
import org.json.JSONObject

enum class OnboardingStep { WELCOME, FINANCE, READY, COMPLETE, LEGACY }

data class OnboardingDraft(
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val accepted: Boolean = false,
    val introSeen: Boolean = false,
    val base: Currency? = null,
    val rates: List<FxRateDraft> = emptyList()
)

/** Installation-local progress: never included in financial backups or demo databases. */
interface OnboardingStorage {
    fun read(): OnboardingDraft
    fun save(draft: OnboardingDraft)
}

class OnboardingPreferences(private val preferences: SharedPreferences) : OnboardingStorage {
    constructor(context: Context) : this(context.getSharedPreferences("onboarding", Context.MODE_PRIVATE))

    // Called before application injection can open the database, including a worker-only launch.
    fun initialize(existingDatabase: Boolean) {
        if (!preferences.contains("draft")) {
            save(OnboardingDraft(step = if (existingDatabase) OnboardingStep.LEGACY else OnboardingStep.WELCOME))
        }
    }

    override fun read(): OnboardingDraft {
        val json = JSONObject(preferences.getString("draft", null) ?: return OnboardingDraft())
        val rates = json.optJSONArray("rates") ?: JSONArray()
        return OnboardingDraft(
            step = OnboardingStep.valueOf(json.getString("step")),
            accepted = json.optBoolean("accepted"), introSeen = json.optBoolean("introSeen"),
            base = json.optString("base").takeIf { it.isNotEmpty() }?.let(Currency::of),
            rates = (0 until rates.length()).map { index ->
                val row = rates.getJSONObject(index)
                FxRateDraft(Currency.of(row.getString("currency")), row.getString("value"))
            })
    }

    override fun save(draft: OnboardingDraft) {
        val json = JSONObject().put("step", draft.step.name).put("accepted", draft.accepted)
            .put("introSeen", draft.introSeen).put("base", draft.base?.code.orEmpty())
            .put("rates", JSONArray().apply {
                draft.rates.forEach { put(JSONObject().put("currency", it.sourceCurrency.code).put("value", it.rateInput)) }
            })
        // Commit success is required before advancing or applying a language that recreates the Activity.
        check(preferences.edit().putString("draft", json.toString()).commit())
    }
}
