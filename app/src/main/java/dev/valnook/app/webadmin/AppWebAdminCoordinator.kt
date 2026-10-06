package dev.valnook.app.webadmin

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.valnook.app.di.AppSessionManager
import dev.valnook.data.webadmin.*
import dev.valnook.domain.webadmin.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppWebAdminCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: AppSessionManager,
    private val clock: Clock
) : WebAdminService, LocalWebServerCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()
    private val pairing = PairingCoordinator()
    private val server = LocalWebServer()
    private val resolver = LanAddressResolver(context)
    private val api = WebAdminApiRouter(context, sessions) { notifyDataChanged(it) }
    private val mutable = MutableStateFlow(WebAdminRuntimeState())
    override val state: StateFlow<WebAdminRuntimeState> = mutable.asStateFlow()

    @Volatile private var binding: LocalWebServerBinding? = null
    @Volatile private var reservationId: String? = null
    private var networkWatch: AutoCloseable? = null
    private var timeoutJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var activePeer: WebSocketPeer? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var activeConnectionKey: String? = null
    private var stopping = false
    private val pendingClients = ConcurrentHashMap<String, ClientSeed>()
    private val socketSessions = ConcurrentHashMap<String, String>()

    override suspend fun start() = lifecycle.withLock {
        if (binding != null || stopping) return@withLock
        val address = resolver.resolvePrivateIpv4()
        if (address == null) {
            mutable.value = WebAdminRuntimeState(error = WebAdminError.NO_PRIVATE_LAN)
            return@withLock
        }
        val reservation = UUID.randomUUID().toString()
        try {
            sessions.reserveWebAdminServer(reservation)
            reservationId = reservation
            val started = server.start(address, this)
            binding = started
            pairing.begin(sessionNow())
            publishWaiting()
            networkWatch = resolver.watch(address) { scope.launch { stop() } }
            timeoutJob = scope.launch { timeoutLoop() }
        } catch (_: Exception) {
            runCatching { server.stop() }
            binding = null
            reservationId = null
            sessions.releaseWebAdminServer(reservation)
            pairing.stop()
            mutable.value = WebAdminRuntimeState(error = WebAdminError.START_FAILED)
        }
    }

    // Use the coordinator scope: an Activity lifecycle job may be cancelled during destruction.
    internal fun onPhoneBackgrounded() {
        scope.launch {
            if (state.value.phase != WebAdminPhase.CLOSED) stopWithReason("PHONE_BACKGROUND")
        }
    }

    override suspend fun stop() = stopWithReason("PHONE_ENDED")

    internal suspend fun stopWithReason(reason: String) {
        val caller = currentCoroutineContext()[Job]
        val shutdown = lifecycle.withLock {
            if ((binding == null && reservationId == null) || stopping) {
                if (!stopping) mutable.value = WebAdminRuntimeState()
                null
            } else {
                stopping = true
                binding = null
                val value = Shutdown(activeSessionId, reservationId, activePeer, networkWatch,
                    timeoutJob?.takeUnless { it === caller }, reconnectJob?.takeUnless { it === caller })
                activeSessionId = null
                activeConnectionKey = null
                reservationId = null
                activePeer = null
                networkWatch = null
                timeoutJob = null
                reconnectJob = null
                pairing.stop()
                pendingClients.clear()
                socketSessions.clear()
                value
            }
        } ?: return
        shutdown.timer?.cancel()
        shutdown.reconnectTimer?.cancel()
        shutdown.networkWatch?.close()
        shutdown.peer?.let { peer ->
            withTimeoutOrNull(500) { runCatching { peer.sendText(buildJsonObject { put("type", "serverClosing"); put("reason", reason) }.toString()) } }
            withTimeoutOrNull(500) { runCatching { peer.close(1000, "closed by phone") } }
        }
        shutdown.webSessionId?.let { sessions.releaseWebWriteLease(it) }
        shutdown.reservationId?.let { sessions.releaseWebAdminServer(it) }
        withTimeoutOrNull(2_500) { runCatching { server.stop() } }
        lifecycle.withLock {
            stopping = false
            mutable.value = WebAdminRuntimeState()
        }
    }

    override suspend fun handle(request: WebHttpRequest): WebHttpResponse {
        val currentBinding = binding ?: return error(410, "SESSION_EXPIRED", true)
        val origin = origin(currentBinding)
        if (!validHost(request, currentBinding)) return error(400, "INVALID_HOST")
        val mutation = request.method in setOf("POST", "PUT", "PATCH", "DELETE")
        val suppliedOrigin = request.headers["origin"]
        if ((mutation && suppliedOrigin != origin) || (suppliedOrigin != null && suppliedOrigin != origin)) {
            return error(403, "INVALID_ORIGIN")
        }
        if (request.path == "/" || request.path in STATIC_PATHS) return static(request.path, currentBinding)
        if (request.path == "/api/v1/pair/qr" && request.method == "POST") return pair(request, qr = true)
        if (request.path == "/api/v1/pair/code" && request.method == "POST") return pair(request, qr = false)
        val sessionSecret = cookie(request.headers["cookie"]) ?: bearer(request.headers["authorization"])
        if (request.path == "/api/v1/session/resume" && request.method == "POST") {
            val resumed = sessionSecret?.let { pairing.resume(it, sessionNow()) }
                ?: return error(401, "SESSION_EXPIRED", clearCookie = true)
            return json(200, buildJsonObject {
                put("status", "resumable")
                put("csrfToken", resumed.csrfToken)
            }.toString())
        }

        val csrf = request.headers["x-valnook-csrf"]
        val sessionId = pairing.authenticate(sessionSecret, csrf, mutation, sessionNow())
            ?: return error(401, "SESSION_EXPIRED", true, clearCookie = true)
        if (request.path == "/api/v1/session/end" && request.method == "POST") {
            disconnectToWaiting(sessionId)
            return json(200, buildJsonObject { put("status", "ended") }.toString(), clearCookie = true)
        }
        return api.handle(sessionId, request)
    }

    override suspend fun admitWebSocket(request: WebHttpRequest): WebSocketAdmission = lifecycle.withLock {
        val currentBinding = binding ?: return@withLock WebSocketAdmission(false, status = 410)
        if (!validHost(request, currentBinding) || request.headers["origin"] != origin(currentBinding)) {
            return@withLock WebSocketAdmission(false, status = 403)
        }
        val now = sessionNow()
        val sessionId = (cookie(request.headers["cookie"]) ?: socketTicket(request.query))
            ?.let { pairing.activate(it, now) }
            ?: return@withLock WebSocketAdmission(false, status = 409)
        try {
            if (activeSessionId == null) {
                sessions.acquireWebWriteLease(sessionId)
                activeSessionId = sessionId
            } else if (activeSessionId != sessionId) {
                return@withLock WebSocketAdmission(false, status = 409)
            }
            val connectionKey = UUID.randomUUID().toString()
            socketSessions[connectionKey] = sessionId
            WebSocketAdmission(true, connectionKey)
        } catch (_: WebAdminException) {
            pairing.revokeAndWait(sessionNow())
            publishWaiting()
            WebSocketAdmission(false, status = 409)
        }
    }

    override suspend fun webSocketOpened(connectionKey: String, peer: WebSocketPeer) {
        var previousPeer: WebSocketPeer? = null
        var accepted = false
        lifecycle.withLock {
            val sessionId = socketSessions[connectionKey]
            if (sessionId == null || activeSessionId != sessionId || binding == null) {
                return@withLock
            }
            reconnectJob?.cancel()
            reconnectJob = null
            previousPeer = activePeer?.takeIf { activeConnectionKey != connectionKey }
            activeConnectionKey = connectionKey
            activePeer = peer
            val existing = mutable.value.client
            val seed = pendingClients.remove(sessionId)
                ?: existing?.let { ClientSeed(it.label, it.remoteAddress) }
                ?: ClientSeed("Browser", "LAN")
            val now = clock.millis()
            mutable.value = WebAdminRuntimeState(WebAdminPhase.ACTIVE, origin(requireNotNull(binding)),
                client = WebAdminClient(seed.label, seed.remoteAddress, existing?.connectedAtMs ?: now, now))
            accepted = true
        }
        if (!accepted) {
            socketSessions.remove(connectionKey)
            peer.close(1008, "expired")
            return
        }
        previousPeer?.let { runCatching { it.close(1000, "replaced") } }
        peer.sendText(buildJsonObject { put("type", "ready"); put("idleRemainingMs", pairing.idleRemaining(sessionNow())) }.toString())
    }

    override suspend fun webSocketMessage(connectionKey: String, text: String) {
        val type = if (text == "heartbeat") "heartbeat" else runCatching {
            Json.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.content
        }.getOrNull()
        if (type !in setOf("heartbeat", "activity")) return
        val sessionId = socketSessions[connectionKey] ?: return
        if (activeConnectionKey != connectionKey) return
        val now = sessionNow()
        if (!pairing.heartbeat(sessionId, now)) return
        if (type == "activity") pairing.activity(sessionId, now)
        val current = mutable.value
        val client = current.client ?: return
        mutable.value = current.copy(client = client.copy(lastSeenAtMs = clock.millis()))
        activePeer?.sendText(buildJsonObject {
            put("type", "idle"); put("idleRemainingMs", pairing.idleRemaining(now))
        }.toString())
    }

    override suspend fun webSocketClosed(connectionKey: String) {
        val sessionId = socketSessions.remove(connectionKey) ?: return
        lifecycle.withLock {
            if (activeConnectionKey != connectionKey || activeSessionId != sessionId || binding == null) {
                return@withLock
            }
            activeConnectionKey = null
            activePeer = null
            reconnectJob?.cancel()
            reconnectJob = scope.launch {
                delay(WEB_SOCKET_RECONNECT_GRACE_MS)
                disconnectToWaiting(sessionId)
            }
        }
    }

    private suspend fun pair(request: WebHttpRequest, qr: Boolean): WebHttpResponse = lifecycle.withLock {
        val body = runCatching { Json.parseToJsonElement(request.body.toString(Charsets.UTF_8)).jsonObject }
            .getOrNull() ?: return@withLock error(400, "FORMAT")
        val input = body[if (qr) "token" else "code"]?.jsonPrimitive?.content.orEmpty()
        if ((!qr && !CODE_PATTERN.matches(input)) || (qr && input.length !in 20..200)) {
            return@withLock error(400, "FORMAT")
        }
        val result = if (qr) pairing.pairQr(input, request.remoteAddress, sessionNow())
            else pairing.pairCode(input, request.remoteAddress, sessionNow())
        when (result) {
            is PairingResult.Granted -> {
                pendingClients[result.grant.sessionId] = ClientSeed(clientLabel(request.headers["user-agent"]),
                    request.remoteAddress.take(64))
                json(200, buildJsonObject {
                    put("status", "paired")
                    put("csrfToken", result.grant.csrfToken)
                    put("sessionToken", result.grant.cookieSecret)
                }.toString(), headers = mapOf("Set-Cookie" to
                    "$WEB_SESSION_COOKIE=${result.grant.cookieSecret}; HttpOnly; SameSite=Lax; Path=/"))
            }
            is PairingResult.Rejected -> {
                if (result.codeRotated) publishWaiting()
                when (result.reason) {
                    PairingFailure.BUSY -> error(409, "SESSION_BUSY")
                    PairingFailure.RATE_LIMITED -> error(429, "RATE_LIMITED")
                    PairingFailure.CLOSED, PairingFailure.EXPIRED -> error(410, "SESSION_EXPIRED", true)
                    PairingFailure.INVALID -> error(401,
                        if (result.codeRotated) "PAIR_CODE_ROTATED" else "PAIR_INVALID")
                }
            }
        }
    }

    private suspend fun disconnectToWaiting(sessionId: String) {
        val caller = currentCoroutineContext()[Job]
        var timer: Job? = null
        var peer: WebSocketPeer? = null
        val release = lifecycle.withLock {
            if (activeSessionId != sessionId || binding == null) return@withLock false
            timer = reconnectJob?.takeUnless { it === caller }
            reconnectJob = null
            peer = activePeer
            activePeer = null
            activeConnectionKey = null
            activeSessionId = null
            socketSessions.filterValues { it == sessionId }.keys.forEach { socketSessions.remove(it) }
            sessions.releaseWebWriteLease(sessionId)
            pairing.revokeAndWait(sessionNow())
            publishWaiting()
            true
        }
        if (!release) return
        timer?.cancel()
        peer?.let { runCatching { it.close(1000, "session ended") } }
    }

    private suspend fun timeoutLoop() {
        while (currentCoroutineContext().isActive) {
            delay(1_000)
            val now = sessionNow()
            val current = mutable.value
            if (current.phase == WebAdminPhase.ACTIVE && pairing.activeExpired(now)) {
                stopWithReason("IDLE_TIMEOUT")
                return
            }
            if (current.phase == WebAdminPhase.WAITING) {
                pairing.pendingExpired(now)
                if (pairing.waitingExpired(now)) {
                    stop()
                    return
                }
                publishWaiting()
            }
        }
    }

    private fun sessionNow(): Long = android.os.SystemClock.elapsedRealtime()

    private fun publishWaiting() {
        val currentBinding = binding ?: return
        val public = pairing.state()
        val token = public.qrToken
        mutable.value = WebAdminRuntimeState(
            phase = WebAdminPhase.WAITING,
            url = origin(currentBinding),
            qrContent = token?.let { "${origin(currentBinding)}/#pair=$it" },
            pairingCode = public.pairingCode
        )
    }

    private suspend fun notifyDataChanged(generation: Long) {
        activePeer?.let { runCatching { it.sendText("{\"type\":\"dataChanged\",\"dataGeneration\":$generation}") } }
    }

    private fun static(path: String, value: LocalWebServerBinding): WebHttpResponse {
        val asset = when (path) {
            "/" -> "webadmin/index.html"
            in STATIC_PATHS -> "webadmin${path}"
            else -> return error(404, "NOT_FOUND")
        }
        val type = when {
            asset.endsWith(".html") -> "text/html; charset=utf-8"
            asset.endsWith(".js") -> "text/javascript; charset=utf-8"
            asset.endsWith(".svg") -> "image/svg+xml"
            else -> "text/css; charset=utf-8"
        }
        val bytes = runCatching { context.assets.open(asset).use { it.readBytes() } }.getOrNull()
            ?: return error(404, "NOT_FOUND")
        val webSocketOrigin = origin(value).replaceFirst("http://", "ws://")
        return WebHttpResponse(200, type, bytes, SECURITY_HEADERS + mapOf(
            "Content-Security-Policy" to "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; connect-src 'self' $webSocketOrigin; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        ))
    }

    private fun validHost(request: WebHttpRequest, value: LocalWebServerBinding): Boolean =
        request.headers["host"] == "${value.address}:${value.port}"

    private fun origin(value: LocalWebServerBinding): String = "http://${value.address}:${value.port}"

    private fun cookie(header: String?): String? = header?.split(';')?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$WEB_SESSION_COOKIE=") }?.substringAfter('=')?.takeIf { it.length in 20..200 }

    private fun bearer(header: String?): String? = header?.takeIf { it.startsWith("Bearer ") }
        ?.removePrefix("Bearer ")?.takeIf { it.length in 20..200 }

    private fun socketTicket(query: String): String? = query.split('&')
        .firstOrNull { it.startsWith("ticket=") }
        ?.substringAfter('=')
        ?.takeIf { it.length in 20..200 && it.all { char -> char.isLetterOrDigit() || char == '-' || char == '_' } }

    private fun clientLabel(userAgent: String?): String {
        val value = userAgent.orEmpty()
        val browser = when {
            "Edg/" in value -> "Edge"
            "Firefox/" in value -> "Firefox"
            "Chrome/" in value -> "Chrome"
            "Safari/" in value -> "Safari"
            else -> "Browser"
        }
        val platform = when {
            "Android" in value -> "Android"
            "Windows" in value -> "Windows"
            "Macintosh" in value -> "macOS"
            "Linux" in value -> "Linux"
            else -> null
        }
        return listOfNotNull(browser, platform).joinToString(" · ")
    }

    private fun json(status: Int, value: String, headers: Map<String, String> = emptyMap(),
        clearCookie: Boolean = false): WebHttpResponse {
        val all = SECURITY_HEADERS + headers + if (clearCookie) mapOf("Set-Cookie" to
            "$WEB_SESSION_COOKIE=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0") else emptyMap()
        return WebHttpResponse(status, "application/json; charset=utf-8", value, all)
    }

    private fun error(status: Int, code: String, refresh: Boolean = false,
        clearCookie: Boolean = false): WebHttpResponse = json(status, buildJsonObject {
        put("error", buildJsonObject {
            put("code", code)
            put("messageKey", code.lowercase())
            put("refreshRequired", refresh)
        })
    }.toString(), clearCookie = clearCookie)

    private data class ClientSeed(val label: String, val remoteAddress: String)
    private data class Shutdown(
        val webSessionId: String?,
        val reservationId: String?,
        val peer: WebSocketPeer?,
        val networkWatch: AutoCloseable?,
        val timer: Job?,
        val reconnectTimer: Job?
    )

    private companion object {
        const val WEB_SOCKET_RECONNECT_GRACE_MS = 45_000L
        val CODE_PATTERN = Regex("[0-9]{6}")
        val STATIC_PATHS = setOf("/app.js", "/styles.css", "/core.js", "/pages.js", "/session.js", "/icons.js", "/symbols.js", "/valnook.svg")
        val SECURITY_HEADERS = mapOf(
            "X-Frame-Options" to "DENY"
        )
    }
}
