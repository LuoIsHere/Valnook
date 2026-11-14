package dev.valnook.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import dev.valnook.data.repository.EncryptedPrivateRecords
import dev.valnook.domain.ai.AiCheck
import dev.valnook.domain.ai.AiCheckItem
import dev.valnook.domain.ai.AiCheckReport
import dev.valnook.domain.ai.AiCheckState
import dev.valnook.domain.ai.AiProblem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.File

/** The API key, kept on this device only: never in backups, the web admin, workbooks or logs. */
interface AiKeyStore {
    val saved: StateFlow<Boolean>
    fun read(): String?
    fun write(key: String)
    fun clear()
    /** Clearing all data: the key and its encryption key go. */
    fun destroy()
}

/**
 * The key encrypted like the card backs (AES-GCM, key in Android Keystore), in its own directory under
 * noBackupFilesDir and under its own alias.
 */
class EncryptedAiKeyStore(context: Context) : AiKeyStore {
    private val records = EncryptedPrivateRecords(File(context.noBackupFilesDir, "ai_private"), "valnook_ai_key_v1") { it == NAME }
    override val saved = MutableStateFlow(runCatching { NAME in records.names() }.getOrDefault(false))

    override fun read(): String? = synchronized(this) {
        records.read(NAME)?.let { bytes -> try { String(bytes, Charsets.UTF_8) } finally { bytes.fill(0) } }
    }
    override fun write(key: String) = synchronized(this) { records.write(NAME, key.toByteArray(Charsets.UTF_8)); saved.value = true }
    override fun clear() = synchronized(this) { records.delete(NAME); saved.value = false }
    override fun destroy() = synchronized(this) {
        runCatching { records.delete(NAME) }
        runCatching { records.destroyKey() }
        saved.value = false
    }

    private companion object { const val NAME = "api_key" }
}

class MemoryAiKeyStore(initial: String? = null) : AiKeyStore {
    private var key: String? = initial
    override val saved = MutableStateFlow(initial != null)
    override fun read() = key
    override fun write(key: String) { this.key = key; saved.value = true }
    override fun clear() { key = null; saved.value = false }
    override fun destroy() = clear()
}

/** The last check report, on this device only: a restored or moved setup is checked again. */
interface AiCheckStore {
    val report: StateFlow<AiCheckReport?>
    fun save(report: AiCheckReport)
    fun clear()
}

class PreferencesAiCheckStore(context: Context) : AiCheckStore {
    private val preferences = context.getSharedPreferences("valnook_ai_checks", Context.MODE_PRIVATE)
    override val report = MutableStateFlow(preferences.getString(KEY, null)?.let(::decode))

    override fun save(report: AiCheckReport) {
        preferences.edit().putString(KEY, encode(report)).apply()
        this.report.value = report
    }
    override fun clear() {
        preferences.edit().remove(KEY).apply()
        report.value = null
    }

    private companion object {
        const val KEY = "report"
        fun encode(report: AiCheckReport): String = JsonObject(mapOf(
            "fingerprint" to JsonPrimitive(report.fingerprint), "at" to JsonPrimitive(report.checkedAtMs),
            "results" to JsonObject(report.results.mapKeys { it.key.name }.mapValues { (_, check) ->
                JsonObject(buildMap {
                    put("state", JsonPrimitive(check.state.name))
                    check.problem?.let { put("problem", JsonPrimitive(it.name)) }
                })
            }))).toString()
        fun decode(text: String): AiCheckReport? = runCatching {
            val root = AiJson.format.parseToJsonElement(text).jsonObject
            AiCheckReport(root["fingerprint"]!!.jsonPrimitive.content, root["at"]!!.jsonPrimitive.long,
                root["results"]!!.jsonObject.entries.mapNotNull { (item, value) ->
                    val check = value.jsonObject
                    val key = AiCheckItem.entries.firstOrNull { it.name == item } ?: return@mapNotNull null
                    key to AiCheck(AiCheckState.valueOf(check["state"]!!.jsonPrimitive.content),
                        check["problem"]?.jsonPrimitive?.content?.let { name -> AiProblem.entries.firstOrNull { it.name == name } })
                }.toMap())
        }.getOrNull()
    }
}

class MemoryAiCheckStore : AiCheckStore {
    override val report = MutableStateFlow<AiCheckReport?>(null)
    override fun save(report: AiCheckReport) { this.report.value = report }
    override fun clear() { report.value = null }
}

/** The picture check's image: [code] in large black digits on white, as a JPEG drawn here and now. */
object AiCheckImage {
    fun jpeg(code: String): ByteArray {
        val bitmap = Bitmap.createBitmap(360, 180, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 110f; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER
            }
            canvas.drawText(code, 180f, 90f - (paint.descent() + paint.ascent()) / 2, paint)
            return ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out); out.toByteArray() }
        } finally { bitmap.recycle() }
    }
}
