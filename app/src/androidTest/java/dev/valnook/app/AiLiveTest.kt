package dev.valnook.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.ai.AiCheckImage
import dev.valnook.data.ai.AiChecker
import dev.valnook.data.ai.AiClient
import dev.valnook.data.ai.EncryptedAiKeyStore
import dev.valnook.domain.ai.AiCheckItem
import dev.valnook.domain.ai.AiCheckState
import dev.valnook.domain.ai.AiPresets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real requests to the DeepSeek preset, only with `-e liveAi true` and a test key placed in the app's cache as
 * `ai_test_key` (deleted as soon as it is read). Reports pass/fail and counts only, never the key or any reply.
 * With `-e liveAiStore true` the key is also saved the way the settings page saves it, to try the page itself.
 */
class AiLiveTest {
    @Test fun the_preset_passes_its_checks_against_the_real_service() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveAi") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "ai_test_key")
        val key = try { file.takeIf { it.isFile }?.readText()?.trim().orEmpty() } finally { file.delete() }
        if (key.isEmpty()) throw AssertionError("NO_TEST_KEY")
        if (arguments.getString("liveAiStore") == "true") EncryptedAiKeyStore(context).write(key)
        val connection = AiPresets.DEEPSEEK.connection()
        val client = AiClient()
        val models = runCatching { client.models(connection, key) }
        val results = AiChecker(client, AiCheckImage::jpeg).run(connection, key) {}
        val summary = "models=${models.getOrNull()?.size ?: models.exceptionOrNull()?.message} " +
            "presetListed=${models.getOrNull()?.contains(connection.model)} " +
            results.entries.joinToString(" ") { (item, check) -> "${item.name}=${check.state}${check.problem?.let { "/$it" } ?: ""}" }
        instrumentation.sendStatus(0, Bundle().apply { putString("live_ai", summary) })
        assertEquals(summary, AiCheckState.PASSED, results[AiCheckItem.CONNECT]?.state)
        assertEquals(summary, AiCheckState.PASSED, results[AiCheckItem.TOOLS]?.state)
    }
}
