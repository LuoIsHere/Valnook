package dev.valnook.feature.ai

import dev.valnook.domain.ai.AiPresets
import org.junit.Assert.*
import org.junit.Test

class AiConnectionFormTest {
    @Test fun the_preset_follows_the_address_and_the_address_is_tidied() {
        val preset = AiConnectionForm.of(AiPresets.DEEPSEEK.connection())
        assertEquals("deepseek", preset.connection().presetId)
        // Another model on the preset's address is still the preset.
        assertEquals("deepseek", preset.copy(model = "other").connection().presetId)
        val local = preset.copy(baseUrl = " http://192.0.2.10:11434/v1/chat/completions ").connection()
        assertNull(local.presetId)
        assertEquals("http://192.0.2.10:11434/v1", local.baseUrl)
    }

    @Test fun parameters_are_empty_or_one_object_as_the_data_layer_requires() {
        assertTrue(AiConnectionForm.validParams(""))
        assertTrue(AiConnectionForm.validParams("""{"thinking": {"type": "enabled"}}"""))
        assertFalse(AiConnectionForm.validParams("[1]"))
        assertFalse(AiConnectionForm.validParams("{'a': 1}"))
        assertFalse(AiConnectionForm.validParams("{\"a\": \"" + "x".repeat(4000) + "\"}"))
    }
}
