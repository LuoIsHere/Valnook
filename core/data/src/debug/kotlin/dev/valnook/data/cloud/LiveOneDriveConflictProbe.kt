package dev.valnook.data.cloud

import org.json.JSONObject
import java.security.MessageDigest
import kotlin.random.Random

/**
 * Debug-only check of how the connected OneDrive's picture folder is claimed when something else has the name. It
 * replaces whatever is called bill_images with one made-up case, tries to claim it and checks what is left there.
 * [keep] leaves the case in place for trying the app against it; mode "clear" only removes the folder. Only flags,
 * counts and the step reached leave this class: no names, ids or response bodies.
 */
class LiveOneDriveConflictProbe(private val folderId: String, private val mode: String, private val keep: Boolean = false) {
    data class Result(
        val mode: String,
        /** Claiming threw the conflict, as it must for a folder that is not Valnook's. */
        val claimRefused: Boolean,
        /** Looking it up without claiming found nothing to use. */
        val findEmpty: Boolean,
        /** What was put there is still there and nothing was added. */
        val foreignKept: Boolean,
        /** For "orphan": Valnook's own pictures without the notice are claimed and the notice is written back. */
        val noticeRestored: Boolean = false,
    )

    var step = "start"
        private set

    private val http = UrlConnectionCloudTransport()
    private val drive = folderId.split('|')[0]
    private val root = folderId.split('|')[1]
    private val graph = "https://graph.microsoft.com/v1.0/drives/$drive"
    private val path = "$graph/items/$root:/${ImagePool.FOLDER}"

    suspend fun run(accessToken: String): Result = try { probe(accessToken) } catch (error: Throwable) {
        throw IllegalStateException("step=$step ${error.javaClass.simpleName} ${(error as? DriveRequestException)?.statusCode ?: ""}")
    }

    private suspend fun probe(token: String): Result {
        val auth = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        step = "remove"
        remove(auth)
        if (mode == "clear") return Result(mode, false, false, false)
        step = "set-up"
        val expected: List<Pair<String, Long>> = when (mode) {
            // Someone's own folder of that name with their own file in it.
            "folder" -> { makeFolder(auth); listOf(put(auth, "notes.txt", "synthetic note\n".toByteArray())) }
            // A notice file of the same name that is not Valnook's.
            "notice" -> { makeFolder(auth); listOf(put(auth, ImagePool.NOTICE, "synthetic text, not the marker\n".toByteArray())) }
            // A file, not a folder, called bill_images.
            "file" -> { putFile(auth, "synthetic file\n".toByteArray()); emptyList() }
            // Valnook's pictures with the notice deleted: still Valnook's.
            "orphan" -> {
                makeFolder(auth)
                val bytes = Random(System.nanoTime()).nextBytes(2_000)
                listOf(put(auth, ImagePool.pictureName(sha256(bytes)), bytes))
            }
            else -> error("mode")
        }
        val api = OneDriveRestApi()
        step = "claim"
        val claimed = try { api.claimImageFolder(token, folderId) {} } catch (_: ImageFolderConflict) { null }
        step = "find"
        val found = api.findImageFolder(token, folderId)
        step = "check"
        val result = if (mode == "file") {
            val item = json(http.execute(CloudHttpRequest("GET", path, auth), null))
            Result(mode, claimed == null, found == null, item?.has("file") == true && !item.has("folder"))
        } else {
            val children = children(auth)
            if (mode == "orphan") Result(mode, claimed == null, found == null,
                expected.all { it in children }, children.any { it.first == ImagePool.NOTICE } && claimed != null && found == claimed)
            else Result(mode, claimed == null, found == null, children.toSet() == expected.toSet())
        }
        if (!keep) { step = "remove-after"; remove(auth) }
        return result
    }

    private suspend fun remove(auth: Map<String, String>) {
        val item = json(http.execute(CloudHttpRequest("GET", path, auth), null)) ?: return
        val gone = http.execute(CloudHttpRequest("DELETE", "$graph/items/${item.getString("id")}", auth), null)
        check(gone.status in 200..299) { "remove ${gone.status}" }
    }

    private suspend fun makeFolder(auth: Map<String, String>) {
        val body = JSONObject().put("name", ImagePool.FOLDER).put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail").toString().toByteArray()
        val made = http.execute(CloudHttpRequest("POST", "$graph/items/$root/children",
            auth + ("Content-Type" to "application/json"), body), null)
        check(made.status in 200..299) { "folder ${made.status}" }
    }

    private suspend fun put(auth: Map<String, String>, name: String, bytes: ByteArray): Pair<String, Long> {
        val sent = http.execute(CloudHttpRequest("PUT", "$path/$name:/content",
            auth + ("Content-Type" to "application/octet-stream"), bytes), null)
        check(sent.status in 200..299) { "put ${sent.status}" }
        return name to bytes.size.toLong()
    }

    private suspend fun putFile(auth: Map<String, String>, bytes: ByteArray) {
        val sent = http.execute(CloudHttpRequest("PUT", "$path:/content",
            auth + ("Content-Type" to "application/octet-stream"), bytes), null)
        check(sent.status in 200..299) { "file ${sent.status}" }
    }

    private suspend fun children(auth: Map<String, String>): List<Pair<String, Long>> {
        val page = json(http.execute(CloudHttpRequest("GET", "$path:/children?\$top=200", auth), null)) ?: return emptyList()
        val items = page.getJSONArray("value")
        return (0 until items.length()).map { items.getJSONObject(it) }.map { it.getString("name") to it.optLong("size", -1) }
    }

    private fun json(response: CloudHttpResponse): JSONObject? = when (response.status) {
        404 -> null
        in 200..299 -> JSONObject(response.body.toString(Charsets.UTF_8))
        else -> error("status ${response.status}")
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
