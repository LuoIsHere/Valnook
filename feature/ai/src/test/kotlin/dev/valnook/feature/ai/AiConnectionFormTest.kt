package dev.valnook.feature.ai

import dev.valnook.domain.ai.AiConnection
import org.junit.Assert.*
import org.junit.Test

class AiConnectionFormTest {
    @Test fun the_address_is_tidied_and_no_preset_is_kept() {
        val form = AiConnectionForm.of(AiConnection("old", "https://api.example.test", "model-a"))
        val local = form.copy(baseUrl = " http://192.0.2.10:11434/v1/chat/completions ", model = " model-b ").connection()
        assertNull(local.presetId)
        assertEquals("http://192.0.2.10:11434/v1", local.baseUrl)
        assertEquals("model-b", local.model)
    }

    @Test fun parameters_are_empty_or_one_object_as_the_data_layer_requires() {
        assertTrue(AiConnectionForm.validParams(""))
        assertTrue(AiConnectionForm.validParams("""{"thinking": {"type": "enabled"}}"""))
        assertFalse(AiConnectionForm.validParams("[1]"))
        assertFalse(AiConnectionForm.validParams("{'a': 1}"))
        assertFalse(AiConnectionForm.validParams("{\"a\": \"" + "x".repeat(4000) + "\"}"))
    }
}
