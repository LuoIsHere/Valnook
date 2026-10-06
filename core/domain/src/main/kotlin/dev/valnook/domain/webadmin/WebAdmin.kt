package dev.valnook.domain.webadmin

import dev.valnook.domain.model.AssetSnapshot
import dev.valnook.domain.model.AssetType
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.model.CurrentStatistics
import dev.valnook.domain.model.StatisticsRequest
import dev.valnook.domain.model.StatisticsSeries
import dev.valnook.domain.model.OperationResult
import kotlinx.coroutines.flow.StateFlow

const val WEB_API_VERSION = 1
const val WEB_ASSET_VERSION = 2

enum class WebAdminPhase { CLOSED, WAITING, ACTIVE }

enum class WebAdminError {
    NO_PRIVATE_LAN,
    START_FAILED,
    SESSION_BUSY,
    SESSION_EXPIRED,
    MAINTENANCE_ACTIVE,
    INTERNAL
}

class WebAdminException(val error: WebAdminError) : IllegalStateException(error.name)

data class WebAdminClient(
    val label: String,
    val remoteAddress: String,
    val connectedAtMs: Long,
    val lastSeenAtMs: Long
)

data class WebAdminRuntimeState(
    val phase: WebAdminPhase = WebAdminPhase.CLOSED,
    val url: String? = null,
    val qrContent: String? = null,
    val pairingCode: String? = null,
    val client: WebAdminClient? = null,
    val error: WebAdminError? = null
)

interface WebAdminService {
    val state: StateFlow<WebAdminRuntimeState>
    suspend fun start()
    suspend fun stop()
}

data class WebWriteLease(val sessionId: String, val dataGeneration: Long)
data class WebMutationReceipt(val result: OperationResult, val dataGeneration: Long)

object UnavailableWebAdminService : WebAdminService {
    private val value = kotlinx.coroutines.flow.MutableStateFlow(WebAdminRuntimeState())
    override val state: StateFlow<WebAdminRuntimeState> = value
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
}

enum class WebRecordKind { CASH, DEPOSIT, TRADE }

data class WebRecord(
    val kind: WebRecordKind,
    val id: Long,
    val revision: Long,
    val businessAtMs: Long,
    val accountId: Long,
    val accountName: String,
    val childId: Long?,
    val childName: String,
    val action: String,
    val objectName: String,
    val amount: String,
    val currencyCode: String,
    val quantity: String? = null,
    val unitPrice: String? = null,
    val fee: String? = null,
    val cashLinked: Boolean? = null,
    val linkedCashAccountId: Long? = null,
    val sourceId: Long?,
    val sourceParentId: Long?,
    val note: String,
    val updatedAtMs: Long
)

data class WebRecordCursor(val businessAtMs: Long, val stableKey: String)

data class WebRecordFilter(
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val accountId: Long? = null,
    val childId: Long? = null,
    val kind: WebRecordKind? = null,
    val query: String = "",
    val cursor: WebRecordCursor? = null,
    val pageSize: Int = 50
)

data class WebRecordPage(val items: List<WebRecord>, val nextCursor: WebRecordCursor?)

interface WebAdminReadRepository {
    suspend fun accountIconImage(key: String): ByteArray? = null
    suspend fun generation(): Long
    suspend fun snapshot(): AssetSnapshot
    suspend fun assetTypes(): List<AssetType>
    suspend fun deposits(): List<TermDeposit>
    suspend fun records(filter: WebRecordFilter): WebRecordPage
    suspend fun currentStatistics(): CurrentStatistics
    suspend fun statisticsSeries(request: StatisticsRequest): StatisticsSeries
}
