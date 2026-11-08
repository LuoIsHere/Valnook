package dev.valnook.data.webadmin

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

const val WAITING_IDLE_TIMEOUT_MS = 5 * 60 * 1_000L
const val ACTIVE_IDLE_TIMEOUT_MS = 5 * 60 * 1_000L
const val PENDING_SOCKET_TIMEOUT_MS = 15_000L
/** Each guess is still one in a million, and at most five a minute from one address. */
const val WRONG_CODES_BEFORE_ROTATION = 3
const val WEB_SESSION_COOKIE = "valnook_web_session"
/** A WebSocket ticket is good for one connection within this time, so the session secret never goes in a URL. */
const val SOCKET_TICKET_TTL_MS = 30_000L

data class PairingPublicState(
    val qrToken: String?,
    val pairingCode: String?,
    val waitingExpiresAtMs: Long?,
    val pendingExpiresAtMs: Long?,
    val activeSessionId: String?
)

data class PairingGrant(
    val sessionId: String,
    val cookieSecret: String,
    val csrfToken: String
)

data class PairingResume(
    val sessionId: String,
    val csrfToken: String
)

enum class PairingFailure { CLOSED, INVALID, BUSY, RATE_LIMITED, EXPIRED }

sealed interface PairingResult {
    data class Granted(val grant: PairingGrant) : PairingResult
    data class Rejected(val reason: PairingFailure, val codeRotated: Boolean = false) : PairingResult
}

/** All credentials are process-local. Only SHA-256 of the cookie secret is retained. */
class PairingCoordinator(
    private val random: SecureRandom = SecureRandom()
) {
    private var qrToken: String? = null
    private var pairingCode: String? = null
    /** Wrong codes since the current one was shown; it changes only after a few, so one stray guess does not void it. */
    private var wrongCodes = 0
    private var waitingExpiresAtMs: Long? = null
    private var pending: Session? = null
    private var active: Session? = null
    private val globalAttempts = ArrayDeque<Long>()
    private val sourceAttempts = mutableMapOf<String, ArrayDeque<Long>>()
    private var socketTicket: SocketTicket? = null

    @Synchronized
    fun begin(nowMs: Long): PairingPublicState {
        clearSecrets()
        qrToken = randomToken(32)
        pairingCode = random.nextInt(1_000_000).toString().padStart(6, '0')
        wrongCodes = 0
        waitingExpiresAtMs = nowMs + WAITING_IDLE_TIMEOUT_MS
        return state()
    }

    @Synchronized
    fun stop(): PairingPublicState {
        clearSecrets()
        return state()
    }

    @Synchronized
    fun pairQr(token: String, source: String, nowMs: Long): PairingResult {
        expire(nowMs)
        if (active != null || pending != null) return PairingResult.Rejected(PairingFailure.BUSY)
        val current = qrToken ?: return PairingResult.Rejected(PairingFailure.CLOSED)
        // The QR token cannot be guessed: only wrong ones count, so others using up the limit never block a scan.
        if (!constantEquals(current, token)) {
            return PairingResult.Rejected(if (allowAttempt(source, nowMs)) PairingFailure.INVALID else PairingFailure.RATE_LIMITED)
        }
        touch(nowMs)
        return grant(nowMs)
    }

    @Synchronized
    fun pairCode(code: String, source: String, nowMs: Long): PairingResult {
        expire(nowMs)
        if (active != null || pending != null) return PairingResult.Rejected(PairingFailure.BUSY)
        val current = pairingCode ?: return PairingResult.Rejected(PairingFailure.CLOSED)
        if (!allowAttempt(source, nowMs)) return PairingResult.Rejected(PairingFailure.RATE_LIMITED)
        if (!constantEquals(current, code)) {
            wrongCodes++
            if (wrongCodes < WRONG_CODES_BEFORE_ROTATION) return PairingResult.Rejected(PairingFailure.INVALID)
            pairingCode = random.nextInt(1_000_000).toString().padStart(6, '0')
            wrongCodes = 0
            return PairingResult.Rejected(PairingFailure.INVALID, codeRotated = true)
        }
        touch(nowMs)
        return grant(nowMs)
    }

    @Synchronized
    fun activate(cookieSecret: String, nowMs: Long): String? =
        activateWhere(nowMs) { hashEquals(it.cookieHash, cookieSecret) }

    /**
     * A one-time ticket for the next WebSocket of the session [cookieSecret] belongs to, pending or active; it replaces
     * any earlier ticket. Null when the secret or the CSRF token does not match.
     */
    @Synchronized
    fun issueSocketTicket(cookieSecret: String, csrfToken: String?, nowMs: Long): String? {
        expire(nowMs)
        if (activeExpired(nowMs)) return null
        val value = listOfNotNull(pending, active).firstOrNull { hashEquals(it.cookieHash, cookieSecret) } ?: return null
        if (csrfToken == null || !constantEquals(value.csrfToken, csrfToken)) return null
        val ticket = randomToken(32)
        socketTicket = SocketTicket(sha256(ticket), value.sessionId, nowMs + SOCKET_TICKET_TTL_MS)
        return ticket
    }

    /** Like [activate], with a ticket from [issueSocketTicket]; a matching ticket is used up, even if activation fails. */
    @Synchronized
    fun activateWithTicket(ticket: String, nowMs: Long): String? {
        val held = socketTicket ?: return null
        if (nowMs >= held.expiresAtMs) {
            socketTicket = null
            return null
        }
        if (!MessageDigest.isEqual(held.hash, sha256(ticket))) return null
        socketTicket = null
        return activateWhere(nowMs) { it.sessionId == held.sessionId }
    }

    private fun activateWhere(nowMs: Long, matches: (Session) -> Boolean): String? {
        expire(nowMs)
        if (activeExpired(nowMs)) return null
        pending?.let { value ->
            if (!matches(value)) return null
            pending = null
            active = value.copy(pendingExpiresAtMs = null, lastSeenAtMs = nowMs, lastActivityAtMs = nowMs)
            return value.sessionId
        }
        val value = active ?: return null
        if (!matches(value)) return null
        active = value.copy(lastSeenAtMs = nowMs)
        return value.sessionId
    }

    @Synchronized
    fun resume(cookieSecret: String, nowMs: Long): PairingResume? {
        expire(nowMs)
        if (activeExpired(nowMs)) return null
        val value = listOfNotNull(pending, active).firstOrNull { hashEquals(it.cookieHash, cookieSecret) }
            ?: return null
        return PairingResume(value.sessionId, value.csrfToken)
    }

    @Synchronized
    fun authenticate(cookieSecret: String?, csrfToken: String? = null, mutation: Boolean = false,
        nowMs: Long): String? {
        expire(nowMs)
        if (activeExpired(nowMs)) return null
        val value = active ?: return null
        if (cookieSecret == null || !hashEquals(value.cookieHash, cookieSecret)) return null
        if (mutation && (csrfToken == null || !constantEquals(value.csrfToken, csrfToken))) return null
        return value.sessionId
    }

    @Synchronized
    fun heartbeat(sessionId: String, nowMs: Long): Boolean {
        if (activeExpired(nowMs)) return false
        val value = active?.takeIf { it.sessionId == sessionId } ?: return false
        active = value.copy(lastSeenAtMs = nowMs)
        return true
    }

    @Synchronized
    fun lastSeen(sessionId: String): Long? = active?.takeIf { it.sessionId == sessionId }?.lastSeenAtMs

    @Synchronized
    fun activity(sessionId: String, nowMs: Long): Boolean {
        if (activeExpired(nowMs)) return false
        val value = active?.takeIf { it.sessionId == sessionId } ?: return false
        active = value.copy(lastActivityAtMs = nowMs)
        return true
    }

    @Synchronized
    fun activeExpired(nowMs: Long): Boolean = active?.let { nowMs - it.lastActivityAtMs >= ACTIVE_IDLE_TIMEOUT_MS } == true

    @Synchronized
    fun idleRemaining(nowMs: Long): Long = active?.let {
        (ACTIVE_IDLE_TIMEOUT_MS - (nowMs - it.lastActivityAtMs)).coerceIn(0, ACTIVE_IDLE_TIMEOUT_MS)
    } ?: 0

    @Synchronized
    fun revokeAndWait(nowMs: Long): PairingPublicState {
        clearSecrets()
        return begin(nowMs)
    }

    @Synchronized
    fun pendingExpired(nowMs: Long): Boolean {
        val wasPending = pending != null
        expire(nowMs)
        return wasPending && pending == null && active == null && qrToken != null
    }

    @Synchronized
    fun waitingExpired(nowMs: Long): Boolean {
        expire(nowMs)
        return qrToken == null && pending == null && active == null
    }

    @Synchronized
    fun state(): PairingPublicState = PairingPublicState(qrToken, pairingCode, waitingExpiresAtMs,
        pending?.pendingExpiresAtMs, active?.sessionId)

    private fun grant(nowMs: Long): PairingResult {
        val secret = randomToken(32)
        val session = Session(UUID.randomUUID().toString(), sha256(secret), randomToken(24),
            nowMs + PENDING_SOCKET_TIMEOUT_MS, nowMs)
        pending = session
        qrToken = null
        pairingCode = null
        waitingExpiresAtMs = null
        return PairingResult.Granted(PairingGrant(session.sessionId, secret, session.csrfToken))
    }

    private fun touch(nowMs: Long) {
        waitingExpiresAtMs = nowMs + WAITING_IDLE_TIMEOUT_MS
    }

    private fun expire(nowMs: Long) {
        if (pending?.pendingExpiresAtMs?.let { nowMs >= it } == true) {
            pending = null
            begin(nowMs)
        } else if (pending == null && active == null && waitingExpiresAtMs?.let { nowMs >= it } == true) {
            clearSecrets()
        }
    }

    private fun allowAttempt(source: String, nowMs: Long): Boolean {
        val threshold = nowMs - 60_000L
        while (globalAttempts.firstOrNull()?.let { it < threshold } == true) globalAttempts.removeFirst()
        val local = sourceAttempts.getOrPut(source.take(80)) { ArrayDeque() }
        while (local.firstOrNull()?.let { it < threshold } == true) local.removeFirst()
        if (globalAttempts.size >= 20 || local.size >= 5) return false
        globalAttempts += nowMs
        local += nowMs
        return true
    }

    private fun clearSecrets() {
        qrToken = null
        pairingCode = null
        waitingExpiresAtMs = null
        pending = null
        active = null
        globalAttempts.clear()
        sourceAttempts.clear()
        socketTicket = null
    }

    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun hashEquals(expected: ByteArray, value: String): Boolean =
        MessageDigest.isEqual(expected, sha256(value))

    private fun constantEquals(expected: String, actual: String): Boolean = MessageDigest.isEqual(
        expected.toByteArray(Charsets.UTF_8), actual.toByteArray(Charsets.UTF_8))

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))

    private class SocketTicket(val hash: ByteArray, val sessionId: String, val expiresAtMs: Long)

    private data class Session(
        val sessionId: String,
        val cookieHash: ByteArray,
        val csrfToken: String,
        val pendingExpiresAtMs: Long?,
        val lastSeenAtMs: Long,
        val lastActivityAtMs: Long = lastSeenAtMs
    )
}
