package dev.valnook.data.webadmin

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.origin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

const val WEB_MAX_BODY_BYTES = 256 * 1024
const val WEB_MAX_URL_CHARS = 2_048
const val WEB_MAX_SOCKET_MESSAGE_BYTES = 8 * 1024

data class WebHttpRequest(
    val method: String,
    val path: String,
    val query: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteAddress: String
)

data class WebHttpResponse(
    val status: Int,
    val contentType: String,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap()
) {
    constructor(status: Int, contentType: String, text: String, headers: Map<String, String> = emptyMap()) :
        this(status, contentType, text.toByteArray(Charsets.UTF_8), headers)
}

data class WebSocketAdmission(val accepted: Boolean, val connectionKey: String? = null, val status: Int = 401)

interface WebSocketPeer {
    suspend fun sendText(value: String)
    suspend fun close(code: Short = 1000, reason: String = "closed")
}

interface LocalWebServerCallbacks {
    suspend fun handle(request: WebHttpRequest): WebHttpResponse
    suspend fun admitWebSocket(request: WebHttpRequest): WebSocketAdmission
    suspend fun webSocketOpened(connectionKey: String, peer: WebSocketPeer)
    suspend fun webSocketMessage(connectionKey: String, text: String)
    suspend fun webSocketClosed(connectionKey: String)
}

data class LocalWebServerBinding(val address: String, val port: Int)

class LocalWebServer {
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private var engineScope: CoroutineScope? = null

    suspend fun start(address: String, callbacks: LocalWebServerCallbacks): LocalWebServerBinding {
        check(engine == null)
        // CIO's resolvedConnectors() can wait indefinitely on Android when the configured
        // port is zero. Ask the OS for an ephemeral port on the exact interface first, then
        // bind CIO to that value. This keeps the random-port policy without blocking the UI.
        val port = withContext(Dispatchers.IO) { reserveEphemeralPort(address) }
        // Ktor otherwise inherits the caller's structured-concurrency Job. A long-lived server
        // then prevents start() from returning to Android. Give each engine its own owned scope.
        val createdScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val created = createdScope.embeddedServer(CIO, host = address, port = port) {
                install(WebSockets) {
                    pingPeriodMillis = 10_000
                    timeoutMillis = 30_000
                    maxFrameSize = WEB_MAX_SOCKET_MESSAGE_BYTES.toLong()
                    masking = false
                }
                routing {
                    webSocket("/ws/v1/session") {
                        val request = call.toRequest(ByteArray(0))
                        val admission = callbacks.admitWebSocket(request)
                        if (!admission.accepted || admission.connectionKey == null) {
                            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY,
                                if (admission.status == 409) "busy" else "unauthorized"))
                            return@webSocket
                        }
                        val key = admission.connectionKey
                        val closed = AtomicBoolean(false)
                        val peer = object : WebSocketPeer {
                            override suspend fun sendText(value: String) { send(value) }
                            override suspend fun close(code: Short, reason: String) {
                                if (closed.compareAndSet(false, true)) {
                                    this@webSocket.close(CloseReason(code, reason.take(120)))
                                }
                            }
                        }
                        try {
                            callbacks.webSocketOpened(key, peer)
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    if (text.toByteArray(Charsets.UTF_8).size > WEB_MAX_SOCKET_MESSAGE_BYTES) {
                                        peer.close(1009, "message too large")
                                        break
                                    }
                                    callbacks.webSocketMessage(key, text)
                                }
                            }
                        } finally {
                            callbacks.webSocketClosed(key)
                        }
                    }
                    dispatchAll("/", callbacks)
                    dispatchAll("/{...}", callbacks)
                }
        }
        return try {
            created.start(wait = false)
            engine = created
            engineScope = createdScope
            LocalWebServerBinding(address, port)
        } catch (error: Throwable) {
            runCatching { created.stop(0, 500) }
            createdScope.cancel()
            throw error
        }
    }

    private fun reserveEphemeralPort(address: String): Int = ServerSocket().use { socket ->
        socket.reuseAddress = false
        socket.bind(InetSocketAddress(InetAddress.getByName(address), 0))
        socket.localPort
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        val current = engine ?: return@withContext
        val currentScope = engineScope
        engine = null
        engineScope = null
        try {
            current.stop(100, 1_000)
        } finally {
            currentScope?.cancel()
        }
    }

    private suspend fun ApplicationCall.dispatch(callbacks: LocalWebServerCallbacks) {
        val full = request.path() + request.queryString().let { if (it.isBlank()) "" else "?$it" }
        if (full.length > WEB_MAX_URL_CHARS) {
            send(WebHttpResponse(414, "application/json", "{\"error\":{\"code\":\"FORMAT\"}}"))
            return
        }
        val contentLength = request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > WEB_MAX_BODY_BYTES) {
            send(WebHttpResponse(413, "application/json", "{\"error\":{\"code\":\"LIMIT_EXCEEDED\"}}"))
            return
        }
        val body = readBodyBounded() ?: run {
            send(WebHttpResponse(413, "application/json", "{\"error\":{\"code\":\"LIMIT_EXCEEDED\"}}"))
            return
        }
        send(callbacks.handle(toRequest(body)))
    }

    private suspend fun ApplicationCall.readBodyBounded(): ByteArray? {
        val source = receiveChannel()
        val output = ByteArrayOutputStream(minOf(WEB_MAX_BODY_BYTES, 16 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (!source.isClosedForRead) {
            val read = source.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) continue
            if (output.size() + read > WEB_MAX_BODY_BYTES) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun ApplicationCall.toRequest(body: ByteArray): WebHttpRequest = WebHttpRequest(
        method = request.httpMethod.value,
        path = request.path(),
        query = request.queryString(),
        headers = request.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") },
        body = body,
        remoteAddress = request.origin.remoteHost
    )

    private suspend fun ApplicationCall.send(response: WebHttpResponse) {
        response.headers.forEach { (name, value) -> this.response.header(name, value) }
        this.response.header("X-Content-Type-Options", "nosniff")
        this.response.header("Referrer-Policy", "no-referrer")
        this.response.header("Cache-Control", "no-store")
        respondBytes(response.body, ContentType.parse(response.contentType), HttpStatusCode.fromValue(response.status))
    }

    private fun Route.dispatchAll(path: String, callbacks: LocalWebServerCallbacks) {
        get(path) { call.dispatch(callbacks) }
        post(path) { call.dispatch(callbacks) }
        put(path) { call.dispatch(callbacks) }
        patch(path) { call.dispatch(callbacks) }
        delete(path) { call.dispatch(callbacks) }
        options(path) { call.dispatch(callbacks) }
        head(path) { call.dispatch(callbacks) }
    }
}
