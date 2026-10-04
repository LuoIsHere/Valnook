package dev.valnook.data.webadmin

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.net.InetAddress

class PairingCoordinatorTest {
    private fun coordinator() = PairingCoordinator(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })

    @Test fun wrong_code_rotates_and_old_code_cannot_pair() {
        val value = coordinator()
        val started = value.begin(1_000)
        val first = requireNotNull(started.pairingCode)
        val wrong = value.pairCode(if (first == "000000") "000001" else "000000", "10.0.0.2", 2_000)
        assertEquals(true, (wrong as PairingResult.Rejected).codeRotated)
        val second = requireNotNull(value.state().pairingCode)
        assertNotEquals(first, second)
        assertEquals(started.waitingExpiresAtMs, value.state().waitingExpiresAtMs)
        assertTrue(value.pairCode(first, "10.0.0.3", 3_000) is PairingResult.Rejected)
    }

    @Test fun qr_is_one_time_and_financial_session_waits_for_websocket() {
        val value = coordinator()
        val token = requireNotNull(value.begin(0).qrToken)
        val grant = (value.pairQr(token, "10.0.0.2", 10) as PairingResult.Granted).grant
        assertNull(value.authenticate(grant.cookieSecret, grant.csrfToken, mutation = true, nowMs = 11))
        assertEquals(grant.sessionId, value.activate(grant.cookieSecret, 12))
        assertEquals(grant.sessionId,
            value.authenticate(grant.cookieSecret, grant.csrfToken, mutation = true, nowMs = 13))
        assertTrue(value.pairQr(token, "10.0.0.3", 14) is PairingResult.Rejected)
    }

    @Test fun invalid_qr_does_not_rotate_code_or_extend_waiting_timeout() {
        val value = coordinator()
        val before = value.begin(5_000)
        assertTrue(value.pairQr("invalid", "10.0.0.2", 50_000) is PairingResult.Rejected)
        assertEquals(before.pairingCode, value.state().pairingCode)
        assertEquals(before.waitingExpiresAtMs, value.state().waitingExpiresAtMs)
    }

    @Test fun idle_waiting_and_pending_handshake_expire() {
        val waiting = coordinator()
        waiting.begin(0)
        assertTrue(waiting.waitingExpired(WAITING_IDLE_TIMEOUT_MS))

        val pending = coordinator()
        val state = pending.begin(0)
        pending.pairCode(requireNotNull(state.pairingCode), "10.0.0.2", 1)
        assertTrue(pending.pendingExpired(PENDING_SOCKET_TIMEOUT_MS + 1))
        assertNotNull(pending.state().pairingCode)
    }

    @Test fun source_rate_limit_does_not_disclose_or_rotate_after_limit() {
        val value = coordinator()
        value.begin(0)
        repeat(5) { value.pairCode("xxxxxx", "10.0.0.2", it.toLong()) }
        val code = value.state().pairingCode
        val limited = value.pairCode("yyyyyy", "10.0.0.2", 10)
        assertEquals(PairingFailure.RATE_LIMITED, (limited as PairingResult.Rejected).reason)
        assertEquals(code, value.state().pairingCode)
    }

    @Test fun csrf_is_required_for_mutations_but_not_reads() {
        val value = coordinator()
        val start = value.begin(0)
        val grant = (value.pairCode(requireNotNull(start.pairingCode), "192.168.1.9", 1)
            as PairingResult.Granted).grant
        assertEquals(grant.sessionId, value.activate(grant.cookieSecret, 2))
        assertEquals(grant.sessionId, value.authenticate(grant.cookieSecret, null, mutation = false, nowMs = 3))
        assertNull(value.authenticate(grant.cookieSecret, null, mutation = true, nowMs = 3))
        assertNull(value.authenticate(grant.cookieSecret, "wrong", mutation = true, nowMs = 3))
        assertEquals(grant.sessionId,
            value.authenticate(grant.cookieSecret, grant.csrfToken, mutation = true, nowMs = 3))
    }

    @Test fun active_session_can_reopen_websocket_with_the_same_cookie() {
        val value = coordinator()
        val start = value.begin(0)
        val grant = (value.pairCode(requireNotNull(start.pairingCode), "192.168.1.9", 1)
            as PairingResult.Granted).grant
        assertEquals(grant.sessionId, value.activate(grant.cookieSecret, 2))
        assertEquals(grant.sessionId, value.activate(grant.cookieSecret, 3))
        assertEquals(grant.sessionId,
            value.authenticate(grant.cookieSecret, grant.csrfToken, mutation = true, nowMs = 4))
        assertEquals(3L, value.lastSeen(grant.sessionId))
    }

    @Test fun pending_and_active_sessions_restore_csrf_only_with_the_same_cookie() {
        val value = coordinator()
        val start = value.begin(0)
        val grant = (value.pairCode(requireNotNull(start.pairingCode), "192.168.1.9", 1)
            as PairingResult.Granted).grant
        assertEquals(grant.csrfToken, value.resume(grant.cookieSecret, 2)?.csrfToken)
        assertNull(value.resume("wrong", 2))
        value.activate(grant.cookieSecret, 3)
        assertEquals(grant.csrfToken, value.resume(grant.cookieSecret, 4)?.csrfToken)
    }

    @Test fun second_client_is_busy_and_disconnect_revokes_old_credentials() {
        val value = coordinator()
        val start = value.begin(0)
        val grant = (value.pairQr(requireNotNull(start.qrToken), "192.168.1.9", 1)
            as PairingResult.Granted).grant
        val busy = value.pairCode("123456", "192.168.1.10", 2)
        assertEquals(PairingFailure.BUSY, (busy as PairingResult.Rejected).reason)
        assertEquals(grant.sessionId, value.activate(grant.cookieSecret, 3))
        assertTrue(value.heartbeat(grant.sessionId, 4))
        value.revokeAndWait(5)
        assertNull(value.authenticate(grant.cookieSecret, grant.csrfToken, mutation = true, nowMs = 6))
        assertNull(value.activate(grant.cookieSecret, 6))
        assertNotNull(value.state().pairingCode)
    }

    @Test fun private_lan_filter_accepts_only_rfc1918_ranges() {
        fun allowed(value: String) = LanAddressResolver.isPrivateLan(
            InetAddress.getByName(value) as java.net.Inet4Address)
        listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.254", "192.168.0.1")
            .forEach { assertTrue(it, allowed(it)) }
        listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "172.15.255.255", "172.32.0.1", "8.8.8.8")
            .forEach { assertFalse(it, allowed(it)) }
    }
}
