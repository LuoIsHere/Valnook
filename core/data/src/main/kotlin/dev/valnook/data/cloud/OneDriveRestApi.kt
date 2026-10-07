package dev.valnook.data.cloud

import dev.valnook.domain.cloud.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** All mutable state is remote or request-local; identities cannot leak between accounts. */
internal class OneDriveRestApi(
    private val http: CloudHttpTransport = UrlConnectionCloudTransport(),
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val backupFolderName: String = CLOUD_BACKUP_FOLDER_NAME
) : CloudDriveApi {
    override val provider = CloudProvider.ONEDRIVE

    override suspend fun serverUtcMs(accessToken: String): Long? {
        val response = request("GET", "$GRAPH/me/drive/special/approot?\$select=id", accessToken)
        return response.header("Date")?.let {
            runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
        }
    }

    override suspend fun currentUser(accessToken: String): RemoteBackupUser {
        val user = json(request("GET", "$GRAPH/me?\$select=id,displayName,mail,userPrincipalName", accessToken))
        fun field(name: String) = if (user.isNull(name)) "" else user.optString(name).trim()
        val name = field("displayName")
        val email = field("mail").ifBlank {
            // Guest UPNs are tenant identifiers, not the user's email address.
            field("userPrincipalName").takeUnless { it.contains("#EXT#", ignoreCase = true) }.orEmpty()
        }
        val display = listOf(name, email).filter { it.isNotBlank() }
            .distinctBy { it.lowercase(java.util.Locale.ROOT) }.joinToString(" · ")
        return RemoteBackupUser(user.getString("id"), display.ifBlank { "Microsoft account" })
    }

    override suspend fun resolveOrCreateFolder(accessToken: String): RemoteBackupFolder {
        val app = json(request("GET", "$GRAPH/me/drive/special/approot", accessToken))
        val drive = app.getJSONObject("parentReference").getString("driveId")
        val parent = app.getString("id")
        val folder = childFolder(accessToken, drive, parent, backupFolderName)
        return RemoteBackupFolder(Ref(drive, folder).folderHandle, backupFolderName)
    }

    override suspend fun upload(accessToken: String, folderId: String, localFile: File, fileName: String,
        appProperties: Map<String, String>, beforeRemoteSideEffect: suspend () -> Unit): RemoteBackupFile = withContext(Dispatchers.IO) {
        val root = Ref.folder(folderId)
        val backupId = appProperties.getValue("backupId")
        require(UUID.fromString(backupId).toString() == backupId)
        beforeRemoteSideEffect()
        val directory = childFolder(accessToken, root.drive, root.root, backupId, beforeRemoteSideEffect)
        val ref = root.copy(directory = directory)
        // Persist the attempt identity first, so a lost final upload response remains discoverable.
        writeMetadata(accessToken, ref, appProperties + ("fileName" to fileName), null, beforeRemoteSideEffect)
        beforeRemoteSideEffect()
        val session = json(request("POST", "${ref.directoryUrl}:/snapshot.val_backup:/createUploadSession", accessToken,
            JSONObject().put("item", JSONObject().put("@microsoft.graph.conflictBehavior", "fail")).toString().toByteArray(), guard = beforeRemoteSideEffect))
        val uploadUrl = safeDownloadUrl(session.getString("uploadUrl"))
        val chunk = ByteArray(320 * 1024 * 10)
        var offset = 0L
        localFile.inputStream().use { input ->
            while (offset < localFile.length()) {
                var length = 0
                val expected = minOf(chunk.size.toLong(), localFile.length() - offset).toInt()
                while (length < expected) {
                    val read = input.read(chunk, length, expected - length)
                    if (read < 0) throw DriveRequestException(null, true)
                    length += read
                }
                beforeRemoteSideEffect()
                val response = request("PUT", uploadUrl, null, chunk.copyOf(length), mapOf(
                    "Content-Type" to "application/octet-stream",
                    "Content-Range" to "bytes $offset-${offset + length - 1}/${localFile.length()}"), beforeRemoteSideEffect)
                offset += length
                if (offset < localFile.length() && response.status != 202) throw DriveRequestException(null, true)
                if (offset == localFile.length() && response.status !in listOf(200, 201)) throw DriveRequestException(null, true)
            }
        }
        val archive = json(request("GET", "${ref.directoryUrl}:/snapshot.val_backup", accessToken))
        metadata(accessToken, ref.copy(item = archive.getString("id")).handle)
    }

    override suspend fun metadata(accessToken: String, fileId: String): RemoteBackupFile {
        val ref = Ref.file(fileId)
        // Validate ancestry on every operation; do not trust a handle supplied by UI or disk.
        val directory = json(request("GET", ref.directoryUrl, accessToken))
        if (directory.getJSONObject("parentReference").getString("id") != ref.root) denied()
        val archive = json(request("GET", ref.itemUrl, accessToken))
        if (archive.getJSONObject("parentReference").getString("id") != ref.directory ||
            archive.getString("name") != "snapshot.val_backup") denied()
        val meta = readMetadata(accessToken, ref)
        if (meta.optInt("protocolVersion") != 1) denied()
        val properties = meta.getJSONObject("properties").let { value ->
            value.keys().asSequence().associateWith { value.getString(it) }
        }.toMutableMap()
        if (properties["backupId"] != directory.getString("name") || properties["app"] != "valnook") denied()
        val version = archive.getString("eTag")
        if (meta.optString("archiveId") != ref.item || meta.optString("archiveETag") != version)
            properties["verificationState"] = "uploaded-unverified"
        return RemoteBackupFile(ref.handle, properties["fileName"] ?: "Valnook.val_backup",
            archive.getString("createdDateTime"), archive.getLong("size"), null,
            listOf(ref.folderHandle), properties, archive.has("deleted"), version)
    }

    override suspend fun verify(accessToken: String, file: RemoteBackupFile, expectedSha256: String, expectedSize: Long) {
        super.verify(accessToken, file, expectedSha256, expectedSize)
        if (metadata(accessToken, file.id).version != file.version) invalid()
    }

    override suspend fun markVerified(accessToken: String, fileId: String, appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit): RemoteBackupFile {
        val current = metadata(accessToken, fileId)
        // Recheck bytes here as well: callers cannot publish a verified marker without proof.
        verify(accessToken, current, appProperties.getValue("archiveSha256"), current.size)
        writeMetadata(accessToken, Ref.file(fileId), appProperties, current.version, beforeRemoteSideEffect)
        return metadata(accessToken, fileId)
    }

    override suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): RemoteBackupPage {
        val root = Ref.folder(folderId)
        val url = pageToken?.also(::safeGraphUrl) ?: "${root.rootUrl}/children?\$top=100"
        val page = json(request("GET", url, accessToken))
        val items = page.getJSONArray("value")
        val files = mutableListOf<RemoteBackupFile>()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (!item.has("folder") || runCatching { UUID.fromString(item.getString("name")) }.isFailure) continue
            val ref = root.copy(directory = item.getString("id"))
            try {
                val archive = json(request("GET", "${ref.directoryUrl}:/snapshot.val_backup", accessToken))
                files += metadata(accessToken, ref.copy(item = archive.getString("id")).handle)
            } catch (error: DriveRequestException) {
                if (error.statusCode !in listOf(404, 412)) throw error
            } catch (_: org.json.JSONException) {
                // A user-created or incomplete directory is not a valid restore point.
            } catch (error: CloudBackupException) {
                if (error.error != CloudBackupError.DRIVE_PERMISSION) throw error
            }
        }
        return RemoteBackupPage(files, page.optString("@odata.nextLink").takeIf { it.isNotBlank() })
    }

    override suspend fun trash(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit) {
        val file = metadata(accessToken, fileId)
        if (file.properties["verificationState"] != "verified") invalid()
        val ref = Ref.file(fileId)
        // Delete only our two known files, never unrelated content a user placed in the folder.
        val children = json(request("GET", "${ref.directoryUrl}/children", accessToken))
        val values = children.getJSONArray("value")
        if (children.has("@odata.nextLink") || values.length() != 2 || (0 until values.length()).any {
            values.getJSONObject(it).getString("name") !in setOf("snapshot.val_backup", "metadata.json")
        }) denied()
        val meta = (0 until values.length()).map { values.getJSONObject(it) }
            .first { it.getString("name") == "metadata.json" }
        beforeRemoteSideEffect()
        request("DELETE", ref.itemUrl, accessToken, headers = mapOf("If-Match" to file.version!!), guard = beforeRemoteSideEffect)
        beforeRemoteSideEffect()
        request("DELETE", "$GRAPH/drives/${segment(ref.drive)}/items/${segment(meta.getString("id"))}",
            accessToken, headers = mapOf("If-Match" to meta.getString("eTag")), guard = beforeRemoteSideEffect)
        // Leave an empty directory. Recursively deleting it could race with a user's new file.

    }

    override suspend fun download(accessToken: String, fileId: String, output: OutputStream) {
        val file = metadata(accessToken, fileId)
        downloadContent(accessToken, "${Ref.file(fileId).itemUrl}/content", output, file.version)
    }

    private suspend fun readMetadata(token: String, ref: Ref): JSONObject {
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(value: Int) { if (bytes.size() >= 64 * 1024) denied(); bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (bytes.size() + length > 64 * 1024) denied()
                bytes.write(buffer, offset, length)
            }
        }
        downloadContent(token, "${ref.directoryUrl}:/metadata.json:/content", bounded, null)
        return JSONObject(bytes.toString("UTF-8"))
    }

    private suspend fun writeMetadata(token: String, ref: Ref, properties: Map<String, String>, version: String?,
        guard: suspend () -> Unit) {
        val body = JSONObject().put("protocolVersion", 1).put("properties", JSONObject(properties))
        if (version != null) body.put("archiveId", ref.item).put("archiveETag", version)
        guard()
        request("PUT", "${ref.directoryUrl}:/metadata.json:/content", token,
            body.toString().toByteArray(), mapOf("Content-Type" to "application/json"), guard)
    }

    private suspend fun downloadContent(token: String, url: String, output: OutputStream, version: String?) {
        val response = request("GET", url, token, headers = version?.let { mapOf("If-Match" to it) } ?: emptyMap(), output = output)
        if (response.status == 302) {
            val location = safeDownloadUrl(response.header("Location") ?: denied())
            request("GET", location, null, output = output)
        }
    }

    private suspend fun childFolder(token: String, drive: String, parent: String, name: String, guard: suspend () -> Unit = {}): String {
        val base = "$GRAPH/drives/${segment(drive)}/items/${segment(parent)}"
        val found = try { json(request("GET", "$base:/${segment(name)}", token)) }
            catch (error: DriveRequestException) { if (error.statusCode == 404) null else throw error }
        if (found != null) { if (!found.has("folder")) denied(); return found.getString("id") }
        return try {
            json(request("POST", "$base/children", token, JSONObject().put("name", name)
                .put("folder", JSONObject()).put("@microsoft.graph.conflictBehavior", "fail").toString().toByteArray(), guard = guard)).getString("id")
        } catch (error: DriveRequestException) {
            if (error.statusCode != 409) throw error
            val existing = json(request("GET", "$base:/${segment(name)}", token))
            if (!existing.has("folder")) denied()
            existing.getString("id")
        }
    }

    private suspend fun request(method: String, url: String, token: String?, body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(), guard: suspend () -> Unit = {}, output: OutputStream? = null): CloudHttpResponse {
        if (token != null) safeGraphUrl(url) else safeDownloadUrl(url)
        val request = CloudHttpRequest(method, url, buildMap {
            put("Accept", "application/json")
            if (token != null) put("Authorization", "Bearer $token")
            if (body != null) put("Content-Type", "application/json")
            putAll(headers)
        }, body)
        repeat(3) { attempt ->
            guard()
            val response = try { http.execute(request, output) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: CloudBackupException) { throw error }
                catch (error: DriveRequestException) { throw error }
                catch (_: Exception) { throw DriveRequestException(null, method != "GET") }
            if (response.status in 200..299 || (response.status == 302 && method == "GET" && token != null && output != null)) return response
            if (response.status == 429 && attempt < 2) {
                val seconds = response.header("Retry-After")?.toLongOrNull() ?: 1
                if (seconds !in 0..30) throw DriveRequestException(429, false)
                wait(seconds * 1000)
            } else throw DriveRequestException(response.status, method != "GET" && response.status >= 500)
        }
        throw DriveRequestException(429, false)
    }

    private data class Ref(val drive: String, val root: String, val directory: String = "", val item: String = "") {
        val folderHandle get() = "$drive|$root"
        val handle get() = "$drive|$root|$directory|$item"
        val rootUrl get() = "$GRAPH/drives/${segment(drive)}/items/${segment(root)}"
        val directoryUrl get() = "$GRAPH/drives/${segment(drive)}/items/${segment(directory)}"
        val itemUrl get() = "$GRAPH/drives/${segment(drive)}/items/${segment(item)}"
        companion object {
            fun folder(handle: String): Ref { val p = handle.split('|'); require(p.size == 2 && p.all { it.isNotBlank() }); return Ref(p[0], p[1]) }
            fun file(handle: String): Ref { val p = handle.split('|'); require(p.size == 4 && p.all { it.isNotBlank() }); return Ref(p[0], p[1], p[2], p[3]) }
        }
    }

    companion object {
        private const val GRAPH = "https://graph.microsoft.com/v1.0"
        private fun json(response: CloudHttpResponse) = JSONObject(response.body.toString(Charsets.UTF_8))
        private fun segment(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        private fun denied(): Nothing = throw CloudBackupException(CloudBackupError.DRIVE_PERMISSION)
        private fun invalid(): Nothing = throw CloudBackupException(CloudBackupError.VERIFY_FAILED)
        private fun safeGraphUrl(url: String) {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host != "graph.microsoft.com" || uri.port !in listOf(-1, 443) ||
                uri.userInfo != null || uri.fragment != null || !uri.path.startsWith("/v1.0/")) denied()
        }
        private fun safeDownloadUrl(url: String): String {
            val uri = URI(url)
            if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null ||
                uri.port !in listOf(-1, 443)) denied()
            return url
        }
    }
}
