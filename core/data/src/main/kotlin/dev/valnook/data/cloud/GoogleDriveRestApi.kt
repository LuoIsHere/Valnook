package dev.valnook.data.cloud

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Small streaming Drive v3 client; access tokens are accepted per call and never retained. */
internal class GoogleDriveRestApi : CloudDriveApi {
    override suspend fun serverUtcMs(accessToken: String): Long? = withContext(Dispatchers.IO) {
        val connection = open("GET", "$ABOUT?fields=kind", accessToken)
        try {
            requireSuccess(connection)
            readBody(connection)
            connection.getHeaderFieldDate("Date", -1L).takeIf { it > 0L }
        } catch (error: DriveRequestException) {
            throw error
        } catch (error: IOException) {
            throw DriveRequestException(null, false, error)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun currentUser(accessToken: String): DriveUser {
        val response = JSONObject(requestJson(
            "GET",
            "$ABOUT?fields=${encode("user(displayName,emailAddress)")}",
            accessToken
        ))
        val user = response.optJSONObject("user") ?: JSONObject()
        val email = user.optString("emailAddress").trim()
        val displayName = user.optString("displayName").trim()
        return DriveUser(
            accountReference = email,
            accountDisplay = when {
                email.isNotBlank() && displayName.isNotBlank() -> "$displayName · $email"
                email.isNotBlank() -> email
                else -> displayName
            }
        )
    }

    override suspend fun resolveOrCreateFolder(accessToken: String): DriveFolder {
        val query = "trashed=false and mimeType='application/vnd.google-apps.folder' and " +
            "appProperties has { key='app' and value='valnook' } and " +
            "appProperties has { key='role' and value='backup-root' }"
        val folders = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val page = listRaw(accessToken, query, pageToken)
            folders += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        val valid = folders.sortedWith(compareBy<DriveFile> { it.createdTime }.thenBy { it.id })
        if (valid.isNotEmpty()) return DriveFolder(valid.first().id, valid.first().name)
        val body = JSONObject()
            .put("name", "Valnook_backup")
            .put("mimeType", "application/vnd.google-apps.folder")
            .put("appProperties", JSONObject(mapOf("app" to "valnook", "role" to "backup-root")))
        val response = requestJson("POST", "$FILES?fields=$FILE_FIELDS", accessToken, body.toString())
        return parseFile(response).let { DriveFolder(it.id, it.name) }
    }

    override suspend fun upload(
        accessToken: String,
        folderId: String,
        localFile: File,
        fileName: String,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit
    ): DriveFile = withContext(Dispatchers.IO) {
        beforeRemoteSideEffect()
        val metadata = JSONObject()
            .put("name", fileName)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", JSONObject(appProperties))
        val session = open("POST", "$UPLOAD/files?uploadType=resumable&fields=$FILE_FIELDS", accessToken).apply {
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("X-Upload-Content-Type", BACKUP_MIME)
            setRequestProperty("X-Upload-Content-Length", localFile.length().toString())
            doOutput = true
        }
        try {
            session.outputStream.use { it.write(metadata.toString().toByteArray(StandardCharsets.UTF_8)) }
            requireSuccess(session)
            val location = session.getHeaderField("Location") ?: throw DriveRequestException(session.responseCode, true)
            var offset = 0L
            localFile.inputStream().buffered(CHUNK_SIZE).use { input ->
                val buffer = ByteArray(CHUNK_SIZE)
                while (offset < localFile.length()) {
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                        if (count == buffer.size) break
                    }
                    if (count <= 0) throw DriveRequestException(null, true)
                    beforeRemoteSideEffect()
                    val end = offset + count - 1
                    val upload = open("PUT", location, accessToken).apply {
                        setRequestProperty("Content-Type", BACKUP_MIME)
                        setRequestProperty("Content-Range", "bytes $offset-$end/${localFile.length()}")
                        setFixedLengthStreamingMode(count)
                        doOutput = true
                    }
                    try {
                        upload.outputStream.use { it.write(buffer, 0, count) }
                        val code = upload.responseCode
                        if (code == 308) {
                            offset += count
                            continue
                        }
                        if (code !in 200..299) throw DriveRequestException(code, false)
                        return@withContext parseFile(readBody(upload))
                    } catch (error: DriveRequestException) {
                        throw error
                    } catch (error: IOException) {
                        throw DriveRequestException(null, true, error)
                    } finally {
                        upload.disconnect()
                    }
                }
            }
            throw DriveRequestException(null, true)
        } catch (error: DriveRequestException) {
            throw error
        } catch (error: IOException) {
            throw DriveRequestException(null, true, error)
        } finally {
            session.disconnect()
        }
    }

    override suspend fun metadata(accessToken: String, fileId: String): DriveFile =
        parseFile(requestJson("GET", "$FILES/${encode(fileId)}?fields=$FILE_FIELDS", accessToken))

    override suspend fun markVerified(accessToken: String, fileId: String,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit): DriveFile {
        beforeRemoteSideEffect()
        val body = JSONObject().put("appProperties", JSONObject(appProperties))
        return parseFile(requestJson("PATCH", "$FILES/${encode(fileId)}?fields=$FILE_FIELDS",
            accessToken, body.toString()))
    }

    override suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): DrivePage =
        listRaw(accessToken, "trashed=false and '${escapeQuery(folderId)}' in parents", pageToken)

    override suspend fun trash(accessToken: String, fileId: String,
        beforeRemoteSideEffect: suspend () -> Unit) {
        beforeRemoteSideEffect()
        requestJson("PATCH", "$FILES/${encode(fileId)}?fields=id", accessToken,
            JSONObject().put("trashed", true).toString())
    }

    override suspend fun download(accessToken: String, fileId: String, output: OutputStream) =
        withContext(Dispatchers.IO) {
            val connection = open("GET", "$FILES/${encode(fileId)}?alt=media", accessToken)
            try {
                requireSuccess(connection)
                connection.inputStream.use { it.copyTo(output, 64 * 1024) }
                output.flush()
            } catch (error: DriveRequestException) {
                throw error
            } catch (error: IOException) {
                throw DriveRequestException(null, false, error)
            } finally {
                connection.disconnect()
            }
        }

    private suspend fun listRaw(accessToken: String, query: String, pageToken: String?): DrivePage {
        val url = buildString {
            append(FILES).append("?q=").append(encode(query))
            append("&spaces=drive&pageSize=100&orderBy=createdTime,name")
            append("&fields=").append(encode("nextPageToken,files($FILE_FIELDS)"))
            if (pageToken != null) append("&pageToken=").append(encode(pageToken))
        }
        val value = JSONObject(requestJson("GET", url, accessToken))
        val files = value.optJSONArray("files") ?: JSONArray()
        return DrivePage((0 until files.length()).map { parseFile(files.getJSONObject(it)) },
            value.optString("nextPageToken").takeIf { it.isNotBlank() })
    }

    private suspend fun requestJson(method: String, url: String, token: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val connection = open(method, url, token)
            try {
                if (body != null) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
                }
                requireSuccess(connection)
                readBody(connection)
            } catch (error: DriveRequestException) {
                throw error
            } catch (error: IOException) {
                throw DriveRequestException(null, false, error)
            } finally {
                connection.disconnect()
            }
        }

    private fun open(method: String, url: String, token: String): HttpURLConnection =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
        }

    private fun requireSuccess(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code !in 200..299) throw DriveRequestException(code, false)
    }

    private fun readBody(connection: HttpURLConnection): String =
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    private fun parseFile(raw: String): DriveFile = parseFile(JSONObject(raw))
    private fun parseFile(value: JSONObject): DriveFile {
        val properties = value.optJSONObject("appProperties") ?: JSONObject()
        val parents = value.optJSONArray("parents") ?: JSONArray()
        return DriveFile(
            id = value.getString("id"),
            name = value.optString("name"),
            createdTime = value.optString("createdTime"),
            size = value.optString("size", "0").toLongOrNull() ?: 0L,
            md5Checksum = value.optString("md5Checksum").takeIf(String::isNotBlank),
            parents = (0 until parents.length()).map { parents.getString(it) },
            appProperties = properties.keys().asSequence().associateWith { properties.getString(it) },
            trashed = value.optBoolean("trashed", false)
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
    private fun escapeQuery(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    private companion object {
        const val ABOUT = "https://www.googleapis.com/drive/v3/about"
        const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        const val BACKUP_MIME = "application/octet-stream"
        const val CHUNK_SIZE = 8 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val FILE_FIELDS = "id,name,createdTime,size,md5Checksum,parents,appProperties,trashed,mimeType"
    }
}
