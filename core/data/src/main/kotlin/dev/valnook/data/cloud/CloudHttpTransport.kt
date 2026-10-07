package dev.valnook.data.cloud

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class CloudHttpRequest(val method: String, val url: String,
    val headers: Map<String, String> = emptyMap(), val body: ByteArray? = null) {
    override fun toString() = "CloudHttpRequest(method=$method)"
}
internal data class CloudHttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
    override fun toString() = "CloudHttpResponse(status=$status)"
}

/** Redirects are handled explicitly so bearer tokens never reach a preauthenticated URL. */
internal fun interface CloudHttpTransport {
    suspend fun execute(request: CloudHttpRequest, output: OutputStream?): CloudHttpResponse
}

internal class UrlConnectionCloudTransport : CloudHttpTransport {
    override suspend fun execute(request: CloudHttpRequest, output: OutputStream?) = withContext(Dispatchers.IO) {
        val uri = URI(request.url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.requestMethod = request.method
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { bytes ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val memory = ByteArrayOutputStream()
            if (status in 200..299) connection.inputStream.use { input ->
                val target = output ?: memory
                val limit = if (output == null) 1024L * 1024 else 256L * 1024 * 1024
                val buffer = ByteArray(64 * 1024)
                var count = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val size = input.read(buffer)
                    if (size < 0) break
                    count += size
                    if (count > limit) throw DriveRequestException(null, request.method != "GET")
                    target.write(buffer, 0, size)
                }
            }
            // Never copy error bodies: they may contain personal data or authorization URLs.
            CloudHttpResponse(status, connection.headerFields.filterKeys { it != null }
                .mapValues { it.value.joinToString(",") }, memory.toByteArray())
        } finally { connection.disconnect() }
    }
}
