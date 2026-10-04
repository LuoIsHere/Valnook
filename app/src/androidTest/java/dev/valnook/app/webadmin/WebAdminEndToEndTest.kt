package dev.valnook.app.webadmin

import android.util.Log
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.di.DataMode
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomOverview
import dev.valnook.domain.webadmin.WebAdminPhase
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.net.URI
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

@HiltAndroidTest
class WebAdminEndToEndTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var web: AppWebAdminCoordinator
    @Inject lateinit var database: ValnookDatabase
    @Inject lateinit var sessions: AppSessionManager

    @Before fun inject() = hilt.inject()
    @After fun stop() = runBlocking {
        step("cleanup")
        web.stop()
        if (sessions.session.value.mode == DataMode.DEMO) sessions.exitDemo()
    }

    @Test fun pairing_security_websocket_write_and_revocation_work_over_real_server() = runBlocking<Unit> {
        step("start")
        web.start()
        val waiting = web.state.value
        assertEquals(waiting.error?.name, WebAdminPhase.WAITING, waiting.phase)
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"

        step("static-security")
        val index = http(endpoint, "GET", "/")
        assertEquals(200, index.status)
        assertTrue(index.headers["content-security-policy"].orEmpty().contains("connect-src 'self' ws://${endpoint.host}:${endpoint.port}"))
        assertFalse(index.body.contains("https://"))
        assertEquals("DENY", index.headers["x-frame-options"])
        assertEquals("nosniff", index.headers["x-content-type-options"])

        assertEquals(401, http(endpoint, "GET", "/api/v1/accounts").status)
        assertEquals(401, http(endpoint, "POST", "/api/v1/accounts", origin = origin, body = "{}").status)
        assertEquals(400, http(endpoint, "GET", "/", host = "evil.example").status)
        assertEquals(403, http(endpoint, "POST", "/api/v1/pair/code",
            body = "{\"code\":\"000000\"}").status)
        assertEquals(400, http(endpoint, "POST", "/api/v1/pair/code", origin = origin, body = "{").status)

        step("pairing")
        val firstCode = requireNotNull(web.state.value.pairingCode)
        val wrongCode = if (firstCode == "000000") "000001" else "000000"
        val wrong = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"$wrongCode\"}")
        assertEquals(401, wrong.status)
        assertTrue(wrong.body.contains("PAIR_CODE_ROTATED"))
        val currentCode = requireNotNull(web.state.value.pairingCode)
        assertNotEquals(firstCode, currentCode)

        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"$currentCode\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        assertTrue(paired.headers["set-cookie"].orEmpty().contains("HttpOnly"))
        assertTrue(paired.headers["set-cookie"].orEmpty().contains("SameSite=Lax"))
        val csrf = JSONObject(paired.body).getString("csrfToken")

        step("websocket")
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)
        sendMaskedText(socket, "{\"type\":\"heartbeat\"}")
        assertEquals(409, http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"123456\"}").status)

        step("write")
        val session = http(endpoint, "GET", "/api/v1/session", cookie = cookie)
        assertEquals(200, session.status)
        assertEquals("REAL", JSONObject(session.body).getString("dataMode"))
        assertEquals(200, http(endpoint, "GET",
            "/api/v1/statistics/history?granularity=DAILY&metric=TOTAL_ASSETS", cookie = cookie).status)
        val generation = JSONObject(session.body).getLong("dataGeneration")
        val operationId = UUID.randomUUID().toString()
        val createBody = JSONObject().put("operationId", operationId).put("dataGeneration", generation)
            .put("expectedRevision", JSONObject.NULL).put("name", "<script>alert(1)</script>")
            .put("note", "<img src=x onerror=alert(1)>").put("cashChanges", org.json.JSONArray()).toString()
        assertEquals(401, http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, body = createBody).status)
        val saved = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, body = createBody)
        assertEquals(saved.body, 200, saved.status)
        assertEquals("WEB_ADMIN", database.audit().eventForOperation(operationId)?.source)
        val savedJson = JSONObject(saved.body)
        assertEquals(200, http(endpoint, "GET", "/api/v1/operations/$operationId", cookie = cookie).status)
        val staleBody = JSONObject(createBody)
            .put("operationId", UUID.randomUUID().toString())
            .put("dataGeneration", savedJson.getLong("dataGeneration"))
            .put("expectedRevision", 0)
            .put("name", "stale update")
            .toString()
        val stale = http(endpoint, "PUT", "/api/v1/accounts/${savedJson.getLong("id")}", origin = origin,
            cookie = cookie, csrf = csrf, body = staleBody)
        assertEquals(409, stale.status)
        assertTrue(stale.body.contains("STALE_RECORD"))
        val accounts = http(endpoint, "GET", "/api/v1/accounts", cookie = cookie)
        assertTrue(accounts.body.contains("<script>alert(1)</script>"))

        step("limits")
        val oversized = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, declaredLength = 262_145)
        assertEquals(413, oversized.status)

        step("disconnect")
        socket.close()
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/accounts", cookie = cookie).status)
        assertEquals(200, http(endpoint, "POST", "/api/v1/session/end", origin = origin,
            cookie = cookie, csrf = csrf, body = "{}").status)
        awaitPhase(WebAdminPhase.WAITING)
        assertEquals(401, http(endpoint, "GET", "/api/v1/accounts", cookie = cookie).status)
        step("complete")
    }

    @Test fun demo_mode_serves_and_mutates_only_the_disposable_demo_database() = runBlocking<Unit> {
        val realNames = RoomOverview(database).snapshot().accounts.map { it.name }
        sessions.enterDemo()
        web.start()
        val waiting = web.state.value
        assertEquals(waiting.error?.name, WebAdminPhase.WAITING, waiting.phase)
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"

        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val csrf = JSONObject(paired.body).getString("csrfToken")
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)

        val session = JSONObject(http(endpoint, "GET", "/api/v1/session", cookie = cookie).body)
        assertEquals("DEMO", session.getString("dataMode"))
        val operationId = UUID.randomUUID().toString()
        val body = JSONObject().put("operationId", operationId)
            .put("dataGeneration", session.getLong("dataGeneration"))
            .put("expectedRevision", JSONObject.NULL)
            .put("name", "Demo browser account")
            .put("note", "Disposable Web data")
            .put("cashChanges", org.json.JSONArray()).toString()
        val saved = http(endpoint, "POST", "/api/v1/accounts", origin = origin,
            cookie = cookie, csrf = csrf, body = body)
        assertEquals(saved.body, 200, saved.status)
        assertTrue(sessions.session.value.graph.overview.snapshot().accounts.any {
            it.name == "Demo browser account"
        })
        assertEquals(realNames, RoomOverview(database).snapshot().accounts.map { it.name })

        socket.close()
        web.stop()
        sessions.exitDemo()
        assertEquals(realNames, RoomOverview(database).snapshot().accounts.map { it.name })
    }

    @Test fun protocol_pong_keeps_session_active_without_javascript_timer_heartbeat() = runBlocking<Unit> {
        web.start()
        val waiting = web.state.value
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        val cookie = paired.headers["set-cookie"].orEmpty().substringBefore(';')
        val socket = openWebSocket(endpoint, origin, cookie)
        awaitPhase(WebAdminPhase.ACTIVE)

        val protocolResponder = launch(Dispatchers.IO) { runCatching { respondToProtocolPings(socket) } }
        delay(17_000)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", cookie = cookie).status)

        socket.close()
        protocolResponder.cancelAndJoin()
    }

    @Test fun mobile_browser_bearer_reconnects_without_cookie_or_repairing() = runBlocking<Unit> {
        web.start()
        val waiting = web.state.value
        val endpoint = URI(requireNotNull(waiting.url))
        val origin = "http://${endpoint.host}:${endpoint.port}"
        val paired = http(endpoint, "POST", "/api/v1/pair/code", origin = origin,
            body = "{\"code\":\"${requireNotNull(waiting.pairingCode)}\"}")
        assertEquals(200, paired.status)
        assertTrue(paired.headers["set-cookie"].orEmpty().contains("SameSite=Lax"))
        val pairedBody = JSONObject(paired.body)
        val csrf = pairedBody.getString("csrfToken")
        val sessionToken = pairedBody.getString("sessionToken")

        val first = openWebSocket(endpoint, origin, cookie = "", ticket = sessionToken)
        awaitPhase(WebAdminPhase.ACTIVE)
        val replacement = openWebSocket(endpoint, origin, cookie = "", ticket = sessionToken)
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", bearer = sessionToken).status)

        first.close()
        replacement.close()
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", bearer = sessionToken).status)

        val restored = http(endpoint, "POST", "/api/v1/session/resume", origin = origin,
            bearer = sessionToken, body = "{}")
        assertEquals(200, restored.status)
        assertEquals(csrf, JSONObject(restored.body).getString("csrfToken"))
        assertEquals(401, http(endpoint, "POST", "/api/v1/session/resume", origin = origin,
            bearer = "wrong-session-token-that-is-long-enough", body = "{}").status)

        val resumed = openWebSocket(endpoint, origin, cookie = "", ticket = sessionToken)
        delay(500)
        assertEquals(WebAdminPhase.ACTIVE, web.state.value.phase)
        assertEquals(200, http(endpoint, "GET", "/api/v1/session", bearer = sessionToken).status)
        assertEquals(200, http(endpoint, "POST", "/api/v1/session/end", origin = origin,
            bearer = sessionToken, csrf = csrf, body = "{}").status)
        awaitPhase(WebAdminPhase.WAITING)
        resumed.close()
    }

    private fun step(name: String) = Log.i("WebAdminE2E", "STEP $name")

    private suspend fun awaitPhase(expected: WebAdminPhase) {
        repeat(100) {
            if (web.state.value.phase == expected) return
            delay(50)
        }
        throw AssertionError("Expected $expected but was ${web.state.value}")
    }

    private fun http(
        endpoint: URI,
        method: String,
        path: String,
        origin: String? = null,
        cookie: String? = null,
        bearer: String? = null,
        csrf: String? = null,
        body: String = "",
        host: String = "${endpoint.host}:${endpoint.port}",
        declaredLength: Int? = null
    ): HttpResult = Socket(endpoint.host, endpoint.port).use { socket ->
        socket.soTimeout = 10_000
        val bytes = body.toByteArray(Charsets.UTF_8)
        val request = buildString {
            append("$method $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n")
            origin?.let { append("Origin: $it\r\n") }
            cookie?.let { append("Cookie: $it\r\n") }
            bearer?.let { append("Authorization: Bearer $it\r\n") }
            csrf?.let { append("X-Valnook-CSRF: $it\r\n") }
            if (method != "GET") append("Content-Type: application/json\r\nContent-Length: ${declaredLength ?: bytes.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().write(request)
        if (declaredLength == null && bytes.isNotEmpty()) socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
        readHttp(socket)
    }

    private fun readHttp(socket: Socket): HttpResult {
        val input = socket.getInputStream()
        val headerBytes = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) throw AssertionError("Incomplete HTTP response")
            headerBytes.write(next)
            tail = (tail + next.toChar()).takeLast(4)
        }
        val head = headerBytes.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n").split("\r\n")
        val headers = head.drop(1).mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let {
            line.substring(0, it).lowercase() to line.substring(it + 1).trim()
        } }.toMap()
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < body.size) {
            val count = input.read(body, offset, body.size - offset)
            if (count < 0) throw AssertionError("Incomplete HTTP body")
            offset += count
        }
        return HttpResult(head.first().split(' ')[1].toInt(), headers, body.toString(Charsets.UTF_8))
    }

    private fun openWebSocket(endpoint: URI, origin: String, cookie: String, ticket: String? = null): Socket {
        val socket = Socket(endpoint.host, endpoint.port).apply { soTimeout = 10_000 }
        val key = Base64.getEncoder().encodeToString(ByteArray(16).also(SecureRandom()::nextBytes))
        val target = "/ws/v1/session" + ticket?.let { "?ticket=$it" }.orEmpty()
        val request = "GET $target HTTP/1.1\r\nHost: ${endpoint.host}:${endpoint.port}\r\n" +
            "Origin: $origin\r\n" + cookie.takeIf { it.isNotBlank() }?.let { "Cookie: $it\r\n" }.orEmpty() +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
        val header = ByteArrayOutputStream()
        var tail = ""
        while (!tail.endsWith("\r\n\r\n")) {
            val next = socket.getInputStream().read()
            if (next < 0) break
            header.write(next); tail = (tail + next.toChar()).takeLast(4)
        }
        assertTrue(header.toString(Charsets.ISO_8859_1.name()).startsWith("HTTP/1.1 101"))
        return socket
    }

    private fun sendMaskedText(socket: Socket, value: String) {
        val payload = value.toByteArray(Charsets.UTF_8)
        sendMaskedFrame(socket, 0x1, payload)
    }

    private fun respondToProtocolPings(socket: Socket) {
        val input = socket.getInputStream()
        while (!Thread.currentThread().isInterrupted) {
            val first = input.read()
            if (first < 0) return
            val second = input.read()
            if (second < 0) return
            val masked = second and 0x80 != 0
            val baseLength = second and 0x7f
            val length = when (baseLength) {
                126 -> (input.read() shl 8) or input.read()
                127 -> {
                    var value = 0L
                    repeat(8) { value = (value shl 8) or input.read().toLong() }
                    require(value <= Int.MAX_VALUE)
                    value.toInt()
                }
                else -> baseLength
            }
            val mask = if (masked) ByteArray(4).also { readFully(input, it) } else null
            val payload = ByteArray(length).also { readFully(input, it) }
            mask?.let { key -> payload.indices.forEach { payload[it] =
                (payload[it].toInt() xor key[it % key.size].toInt()).toByte() } }
            when (first and 0x0f) {
                0x9 -> sendMaskedFrame(socket, 0xA, payload)
                0x8 -> return
            }
        }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw java.io.EOFException()
            offset += count
        }
    }

    private fun sendMaskedFrame(socket: Socket, opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(SecureRandom()::nextBytes)
        require(payload.size < 126)
        val frame = ByteArray(2 + 4 + payload.size)
        frame[0] = (0x80 or opcode).toByte(); frame[1] = (0x80 or payload.size).toByte()
        mask.copyInto(frame, 2)
        payload.indices.forEach { frame[6 + it] = (payload[it].toInt() xor mask[it % 4].toInt()).toByte() }
        socket.getOutputStream().write(frame); socket.getOutputStream().flush()
    }

    private data class HttpResult(val status: Int, val headers: Map<String, String>, val body: String)
}
