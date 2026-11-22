package dev.valnook.app

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.ai.AiCheckImage
import dev.valnook.data.ai.AiChecker
import dev.valnook.data.ai.AiClient
import dev.valnook.data.ai.EncryptedAiKeyStore
import dev.valnook.domain.ai.AiCheckItem
import dev.valnook.domain.ai.AiCheckState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Real requests to the DeepSeek preset, only with `-e liveAi true` and a test key placed in the app's cache as
 * `ai_test_key` (deleted as soon as it is read). Reports pass/fail and counts only, never the key or any reply.
 * With `-e liveAiStore true` the key is also saved the way the settings page saves it, to try the page itself.
 * `-e liveAiBaseUrl <address>` checks the preset's model at another compatible address, such as a relay, and
 * `-e liveAiThinkingOff <json>` replaces the thinking off parameters. With `liveAiStore` the connection is also saved
 * to the app's real database, so the settings page shows it.
 */
@OptInTest
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
        // DeepSeek's own service unless told otherwise: the official pass at the end of the version uses it.
        val connection = dev.valnook.domain.ai.AiConnection(null, "https://api.deepseek.com", "deepseek-flash", "",
            """{"thinking": {"type": "disabled"}}""", """{"thinking": {"type": "enabled"}}""").let { official ->
            arguments.getString("liveAiBaseUrl")?.let { official.copy(baseUrl = it) } ?: official
        }.let { connection -> arguments.getString("liveAiThinkingOff")?.let { connection.copy(thinkingOffParams = it) } ?: connection }
        if (arguments.getString("liveAiStore") == "true") android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath("valnook.db").path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT OR IGNORE INTO ai_settings(id) VALUES (1)")
            db.execSQL("UPDATE ai_settings SET preset_id=?,base_url=?,model=?,extra_params=?,thinking_off_params=?,thinking_on_params=? WHERE id=1",
                arrayOf(connection.presetId, connection.baseUrl, connection.model, connection.extraParams,
                    connection.thinkingOffParams, connection.thinkingOnParams))
        }
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
