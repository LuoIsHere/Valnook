package dev.valnook.data.ai

import dev.valnook.domain.ai.AiConnection
import dev.valnook.domain.ai.AiException
import dev.valnook.domain.ai.AiProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

data class AiHttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: ByteArray?,
    val timeoutMs: Int) {
    override fun toString() = "AiHttpRequest(method=$method)"
}

data class AiHttpResponse(val status: Int, val body: String) {
    override fun toString() = "AiHttpResponse(status=$status)"
}

/** Replaced in tests. Throws [IOException] for network trouble and [SocketTimeoutException] for a timeout. */
fun interface AiTransport {
    suspend fun execute(request: AiHttpRequest): AiHttpResponse
}

/**
 * The only connection allowed to use plain `http://`, and only to the address the user typed (a model on their own
 * network). Redirects are not followed, so the key never reaches another address.
 */
class UrlConnectionAiTransport : AiTransport {
    override suspend fun execute(request: AiHttpRequest) = withContext(Dispatchers.IO) {
        val uri = URI(request.url)
        require(uri.scheme in setOf("http", "https") && uri.userInfo == null && uri.fragment == null)
        val connection = uri.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = minOf(15_000, request.timeoutMs)
            connection.readTimeout = request.timeoutMs
            connection.requestMethod = request.method
            request.headers.forEach(connection::setRequestProperty)
            request.body?.let { bytes ->
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_RESPONSE) throw IOException("Response too large")
                }
            }
            AiHttpResponse(status, out.toString(Charsets.UTF_8.name()))
        } finally { connection.disconnect() }
    }

    private companion object { const val MAX_RESPONSE = 4 * 1024 * 1024 }
}

/** Requests to an OpenAI-compatible service. Errors arrive as [AiException]; bodies are never logged. */
class AiClient(private val transport: AiTransport = UrlConnectionAiTransport()) {

    suspend fun models(connection: AiConnection, apiKey: String?): List<String> {
        val response = send(AiHttpRequest("GET", "${connection.baseUrl}/models", headers(apiKey, json = false), null, 15_000))
        return AiJson.models(response.body) ?: throw AiException(AiProblem.BAD_RESPONSE, response.status)
    }

    /**
     * Sends [body] (messages, tools and the like) with the model and the connection's parameters laid over it:
     * the extra parameters, then the thinking ones for [thinking].
     */
    suspend fun chat(connection: AiConnection, apiKey: String?, body: JsonObject, thinking: Boolean, timeoutMs: Int): AiReply {
        val request = request(connection, body, thinking)
        val response = send(AiHttpRequest("POST", "${connection.baseUrl}/chat/completions", headers(apiKey, json = true),
            AiJson.format.encodeToString(JsonObject.serializer(), request).toByteArray(), timeoutMs))
        return AiJson.reply(response.body) ?: throw AiException(AiProblem.BAD_RESPONSE, response.status)
    }

    /** The request body as sent, also what "view the full prompt" shows. */
    fun request(connection: AiConnection, body: JsonObject, thinking: Boolean): JsonObject {
        var request = JsonObject(mapOf("model" to JsonPrimitive(connection.model)) + body)
        request = AiJson.merge(request, AiJson.params(connection.extraParams))
        return AiJson.merge(request, AiJson.params(if (thinking) connection.thinkingOnParams else connection.thinkingOffParams))
    }

    private fun headers(apiKey: String?, json: Boolean) = buildMap {
        put("Accept", "application/json")
        if (json) put("Content-Type", "application/json; charset=utf-8")
        if (!apiKey.isNullOrEmpty()) put("Authorization", "Bearer $apiKey")
    }

    private suspend fun send(request: AiHttpRequest): AiHttpResponse {
        val response = try { transport.execute(request) }
            catch (_: SocketTimeoutException) { throw AiException(AiProblem.TIMEOUT) }
            catch (_: IOException) { throw AiException(AiProblem.OFFLINE) }
        if (response.status in 200..299) return response
        throw AiException(problemOf(response.status, response.body), response.status)
    }

    internal companion object {
        /** What an error status means for the user; some services report an empty balance as 400 with a message. */
        fun problemOf(status: Int, body: String): AiProblem = when {
            status == 401 || status == 403 -> AiProblem.UNAUTHORIZED
            status == 402 || (status in 400..499 && body.contains("insufficient", ignoreCase = true) &&
                body.contains("balance", ignoreCase = true)) -> AiProblem.PAYMENT
            status == 404 -> AiProblem.NOT_FOUND
            status == 408 -> AiProblem.TIMEOUT
            status == 429 -> AiProblem.RATE_LIMITED
            status >= 500 -> AiProblem.SERVER
            status in 300..399 -> AiProblem.BAD_RESPONSE
            else -> AiProblem.REJECTED
        }
    }
}
