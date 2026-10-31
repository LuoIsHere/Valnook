package dev.valnook.domain.fx

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** What started a sync. Only [START] is held back by "already updated today" and the 30-minute retry wait. */
enum class FxSyncTrigger { START, ONBOARDING, BASE_CHANGED, ENABLED, MANUAL }

/** Why the last attempt failed; stored by name. */
enum class FxSyncError { NETWORK, TIMEOUT, RATE_LIMITED, SERVICE, PARSE, STORAGE }

data class FxSyncStatus(
    /** False in the demo, which never goes online. */
    val available: Boolean = false,
    val autoEnabled: Boolean = false,
    val noticeDismissed: Boolean = false,
    val running: Boolean = false,
    val lastSuccessAtMs: Long? = null,
    /** Latest date the source published among the last successful results. */
    val dataDate: LocalDate? = null,
    val lastAttemptAtMs: Long? = null,
    /** Set while the most recent attempt failed; cleared by the next success. */
    val lastError: FxSyncError? = null,
    /** In-use currencies the last successful sync had no rate for. */
    val missingCurrencies: List<String> = emptyList()
)

/** One background sync that needs the user's attention: it failed, or some in-use currencies had no rate. */
data class FxSyncNotice(
    val id: String,
    val error: FxSyncError?,
    val missingCurrencies: List<String>,
    val dataDate: LocalDate?
)

interface FxRateSync {
    fun observeStatus(): Flow<FxSyncStatus>
    /** The latest notice not yet consumed. Manual updates never raise one; they report through the status. */
    fun observeNotice(): Flow<FxSyncNotice?>
    suspend fun consumeNotice(id: String)
    suspend fun refreshNow()
    suspend fun setAutoEnabled(on: Boolean)
}

object UnavailableFxRateSync : FxRateSync {
    override fun observeStatus(): Flow<FxSyncStatus> = flowOf(FxSyncStatus())
    override fun observeNotice(): Flow<FxSyncNotice?> = flowOf(null)
    override suspend fun consumeNotice(id: String) = Unit
    override suspend fun refreshNow() = Unit
    override suspend fun setAutoEnabled(on: Boolean) = Unit
}
