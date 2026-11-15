package dev.valnook.domain.ai

import dev.valnook.domain.model.BillCategory
import kotlinx.coroutines.flow.Flow
import java.net.URI

/** Every tunable number of the AI features, in one place. */
object AiRules {
    const val MY_RULES_MAX = 2000
    const val NICKNAME_MAX = 20
    const val DEFAULT_TIME_MINUTES = 12 * 60
    const val URL_MAX = 500
    const val MODEL_MAX = 200
    const val KEY_MAX = 1000
    const val PARAMS_MAX = 4000
    const val CONTEXT_TURNS = 6
    const val HABITS = 20
    const val MAX_TOKENS_BILLS = 1024
    const val MAX_TOKENS_QUERY = 2048
    const val MAX_TOKENS_THINKING = 8192
    const val TIMEOUT_TEXT_MS = 30_000
    const val TIMEOUT_IMAGE_MS = 60_000
    const val TIMEOUT_THINKING_MS = 120_000
    const val FORMAT_RETRIES = 1
    /** Digits of the code the tool and picture checks ask for. */
    const val CODE_DIGITS = 4
    const val MAX_IMAGES = 4
    const val IMAGE_EDGE = 1280
    const val IMAGE_QUALITY = 80
    /** A conversation quiet this long is not continued: the chat page starts a new one. */
    const val CONVERSATION_IDLE_MS = 60 * 60 * 1000L
    const val KEEP_DAYS = 30L
    /** Drafts read from one reply at most. */
    const val MAX_DRAFTS = 20
    /** Longest edge of the copy of a sent picture kept with the conversation. */
    const val THUMB_EDGE = 320
    /** A conversation's title: the start of its first message. */
    const val TITLE_LENGTH = 16
    /** How far from now a bill's time may be before now is used instead. */
    const val MAX_TIME_DAYS = 366L
}

/**
 * Where AI requests go: an OpenAI-compatible chat completions service. [extraParams] is merged into every request,
 * then [thinkingOffParams] or [thinkingOnParams] by whether deep thinking is on; each is a JSON object or empty.
 */
data class AiConnection(
    val presetId: String?,
    val baseUrl: String,
    val model: String,
    val extraParams: String = "",
    val thinkingOffParams: String = "",
    val thinkingOnParams: String = "",
) {
    /** What a check result belongs to: changing any of these voids it. */
    val fingerprint: String get() = listOf(baseUrl, model, extraParams, thinkingOffParams, thinkingOnParams).joinToString("\u0000")

    val usable: Boolean get() = validBaseUrl(baseUrl) && model.isNotBlank()

    /** Sent without encryption: only for a model on the user's own network. */
    val cleartext: Boolean get() = baseUrl.startsWith("http://", ignoreCase = true)

    val host: String get() = runCatching { URI(baseUrl).host }.getOrNull().orEmpty()

    companion object {
        private const val ENDPOINT = "/chat/completions"

        /** Trims spaces and trailing slashes, and the endpoint itself when the whole address was pasted. */
        fun normalizeBaseUrl(text: String): String {
            var url = text.trim().trimEnd('/')
            if (url.endsWith(ENDPOINT, ignoreCase = true)) url = url.dropLast(ENDPOINT.length).trimEnd('/')
            return url
        }

        /** An http or https address with a host and nothing a request could not carry. */
        fun validBaseUrl(url: String): Boolean {
            if (url.isEmpty() || url.length > AiRules.URL_MAX || url.any { it.isWhitespace() }) return false
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() &&
                uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
        }
    }
}

/** The AI settings that travel with backups; the key, check results and conversations stay on the device. */
data class AiSettings(
    /** Nothing filled in: the user enters the service of their choice; no vendor is assumed. */
    val connection: AiConnection = AiConnection(null, "", ""),
    val enabled: Boolean = false,
    val billChat: Boolean = true,
    /** No longer switched: with AI on, shares go to it (Settings → Quick bookkeeping); kept as backups carry them. */
    val shareText: Boolean = true,
    val shareImage: Boolean = false,
    val query: Boolean = false,
    val thinking: Boolean = false,
    val chat: Boolean = false,
    val sendHabits: Boolean = false,
    /** Used when a bill names a day but no time. */
    val defaultTimeMinutes: Int = AiRules.DEFAULT_TIME_MINUTES,
    /** Null: no account, the bill moves no balance. */
    val defaultCashAccountId: Long? = null,
    /** Null: the expense or income catch-all category. */
    val defaultExpenseCategoryId: Long? = null,
    val defaultIncomeCategoryId: Long? = null,
    val myRules: String = "",
    val userNickname: String = "",
    val aiNickname: String = "",
)

/**
 * What can be used right now: the switches as set, limited by what the saved connection's checks passed. A changed
 * connection voids its checks, and with them every entry, until it is checked again.
 */
data class AiFeatures(
    val enabled: Boolean = false,
    val billChat: Boolean = false,
    val shareText: Boolean = false,
    val shareImage: Boolean = false,
    val thinking: Boolean = false,
    val chat: Boolean = false,
    /** Pictures can be sent in a conversation. */
    val pictures: Boolean = false,
) {
    /** The chat page has something to offer. */
    val chatEntry: Boolean get() = enabled && billChat

    companion object {
        fun of(settings: AiSettings, report: AiCheckReport?): AiFeatures {
            val on = settings.enabled && report?.usable == true
            if (!on) return AiFeatures()
            val image = report!!.of(AiCheckItem.IMAGE).state == AiCheckState.PASSED
            val thinking = report.of(AiCheckItem.THINKING).state == AiCheckState.PASSED &&
                settings.connection.thinkingOnParams.isNotBlank()
            // A share goes to the AI whenever it is on; pictures once the model has passed the picture check.
            return AiFeatures(true, settings.billChat, true, image,
                settings.thinking && thinking, settings.chat, image)
        }
    }
}

enum class AiCheckItem { CONNECT, TOOLS, IMAGE, THINKING }

enum class AiCheckState {
    NOT_CHECKED,
    PASSED,
    /** The service said no or answered wrong: the model cannot do this. */
    FAILED,
    /** Network trouble, a timeout or a busy service: try again. */
    UNKNOWN,
    /** Deep thinking without parameters to switch it on. */
    NOT_CONFIGURED,
}

enum class AiProblem {
    OFFLINE, TIMEOUT, UNAUTHORIZED, PAYMENT, NOT_FOUND, RATE_LIMITED, SERVER, REJECTED, BAD_RESPONSE,
    NO_TOOL_CALL, WRONG_ANSWER, NO_THINKING, NO_KEY_STORAGE,
    /** The service still thinks with thinking off and wants that thinking back: the thinking off parameters do not work there. */
    THINKING_STILL_ON,
    /** The drafts the model wrote could not be read, even after asking again. */
    UNREADABLE_DRAFTS;

    /** Whether trying again later could give another answer, so the capability is left undecided. */
    val transient: Boolean get() = this in setOf(OFFLINE, TIMEOUT, UNAUTHORIZED, PAYMENT, RATE_LIMITED, SERVER, NO_KEY_STORAGE)
}

data class AiCheck(val state: AiCheckState = AiCheckState.NOT_CHECKED, val problem: AiProblem? = null)

/** The checks of one connection ([fingerprint]) at [checkedAtMs]. */
data class AiCheckReport(val fingerprint: String, val checkedAtMs: Long, val results: Map<AiCheckItem, AiCheck>) {
    fun of(item: AiCheckItem): AiCheck = results[item] ?: AiCheck()
    /** The two required checks passed: the AI features can be switched on. */
    val usable: Boolean get() = of(AiCheckItem.CONNECT).state == AiCheckState.PASSED && of(AiCheckItem.TOOLS).state == AiCheckState.PASSED
}

/**
 * A sub-account offered as the default account, and sent to the model by id. [credit] is a credit card's; [hidden]
 * ones (not shown on the accounts page) are not sent; [lastUsedMs], its latest bill, decides what a long list keeps.
 */
data class AiAccountChoice(val id: Long, val name: String, val currencyCode: String, val favorite: Boolean,
    val credit: Boolean = false, val hidden: Boolean = false, val lastUsedMs: Long? = null)

data class AiChoices(val accounts: List<AiAccountChoice> = emptyList(), val categories: List<BillCategory> = emptyList())

class AiException(val problem: AiProblem, val status: Int? = null) : Exception(problem.name)

/** Settings → AI: the connection, its checks and what is sent with every request. */
interface AiService {
    fun observeSettings(): Flow<AiSettings>
    /** The report for the saved connection; null once the connection changed or it was never checked. */
    fun observeChecks(): Flow<AiCheckReport?>
    fun observeKeySaved(): Flow<Boolean>
    fun observeChoices(): Flow<AiChoices>
    fun observeFeatures(): Flow<AiFeatures>
    /** [apiKey]: null keeps the saved key, empty removes it. */
    suspend fun saveConnection(connection: AiConnection, apiKey: String?)
    suspend fun saveDefaults(timeMinutes: Int, cashAccountId: Long?, expenseCategoryId: Long?, incomeCategoryId: Long?)
    suspend fun savePersonal(myRules: String, userNickname: String, aiNickname: String)
    /** The master switch; switching on needs the required checks passed for the saved connection. */
    suspend fun setEnabled(enabled: Boolean)
    /** The feature switches: everything in [AiSettings] after [AiSettings.enabled]. */
    suspend fun saveFeatures(billChat: Boolean, shareText: Boolean, shareImage: Boolean, thinking: Boolean, chat: Boolean,
        sendHabits: Boolean)
    /** The models [connection] offers, with [apiKey] or else the saved key; throws [AiException]. */
    suspend fun fetchModels(connection: AiConnection, apiKey: String?): List<String>
    /** Runs every check against the saved connection, telling [progress] which one runs. */
    suspend fun check(progress: (AiCheckItem) -> Unit = {}): AiCheckReport
    /** Exactly what a bill request sends before the user's words, tools included. */
    suspend fun promptPreview(): String
}
