package dev.valnook.domain

import dev.valnook.domain.ai.AiCheck
import dev.valnook.domain.ai.AiCheckItem
import dev.valnook.domain.ai.AiCheckReport
import dev.valnook.domain.ai.AiCheckState
import dev.valnook.domain.ai.AiConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiConnectionTest {
    @Test fun a_pasted_address_is_trimmed_to_the_base() {
        assertEquals("https://api.example.test", AiConnection.normalizeBaseUrl(" https://api.example.test/ "))
        assertEquals("https://api.example.test/v1", AiConnection.normalizeBaseUrl("https://api.example.test/v1/chat/completions"))
        assertEquals("http://192.0.2.10:11434/v1", AiConnection.normalizeBaseUrl("http://192.0.2.10:11434/v1/chat/completions/"))
    }

    @Test fun only_plain_http_or_https_addresses_are_valid() {
        assertTrue(AiConnection.validBaseUrl("https://api.example.test"))
        assertTrue(AiConnection.validBaseUrl("http://localhost:8080/v1"))
        assertFalse(AiConnection.validBaseUrl(""))
        assertFalse(AiConnection.validBaseUrl("ftp://api.example.test"))
        assertFalse(AiConnection.validBaseUrl("https://user:pass@api.example.test"))
        assertFalse(AiConnection.validBaseUrl("https://api.example.test/?key=1"))
        assertFalse(AiConnection.validBaseUrl("https://api.example.test/#x"))
        assertFalse(AiConnection.validBaseUrl("https://api example.test"))
        assertFalse(AiConnection.validBaseUrl("api.example.test"))
    }

    @Test fun any_change_to_the_address_model_or_parameters_voids_a_check() {
        val base = AiConnection(null, "https://api.example.test/v1", "model-a", "",
            """{"thinking": {"type": "disabled"}}""", """{"thinking": {"type": "enabled"}}""")
        assertTrue(base.usable)
        assertFalse(base.cleartext)
        assertEquals("api.example.test", base.host)
        // Nothing is filled in until the user does.
        assertFalse(dev.valnook.domain.ai.AiSettings().connection.usable)
        listOf(base.copy(baseUrl = "https://other.test"), base.copy(model = "other"), base.copy(extraParams = "{}"),
            base.copy(thinkingOffParams = ""), base.copy(thinkingOnParams = "")).forEach {
            assertNotEquals(base.fingerprint, it.fingerprint)
        }
        // The stored preset id (no longer used) is not part of what was checked.
        assertEquals(base.fingerprint, base.copy(presetId = "old").fingerprint)
    }

    @Test fun the_features_need_the_two_required_checks() {
        fun report(connect: AiCheckState, tools: AiCheckState) = AiCheckReport("x", 0,
            mapOf(AiCheckItem.CONNECT to AiCheck(connect), AiCheckItem.TOOLS to AiCheck(tools)))
        assertTrue(report(AiCheckState.PASSED, AiCheckState.PASSED).usable)
        assertFalse(report(AiCheckState.PASSED, AiCheckState.UNKNOWN).usable)
        assertFalse(report(AiCheckState.FAILED, AiCheckState.PASSED).usable)
        assertEquals(AiCheckState.NOT_CHECKED, report(AiCheckState.PASSED, AiCheckState.PASSED).of(AiCheckItem.IMAGE).state)
    }
}
