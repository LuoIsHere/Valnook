package dev.valnook.data.cloud

import java.io.OutputStream
import java.net.URI
import org.json.JSONObject

/**
 * Debug-only, read-only check of how the connected OneDrive answers the backup listing. Any request other than
 * a GET is refused before it is sent, and only counts leave this class: no names, ids or response bodies.
 */
class LiveOneDriveListingProbe(private val folderId: String) {
    /**
     * [directories] directories listed; [counted] of them came with a child count; [emptied] said they hold nothing
     * and were skipped; [asked] were asked for their archive (before this, every one was); [backups] restore points.
     */
    data class Result(val directories: Int, val counted: Int, val emptied: Int, val asked: Int, val backups: Int,
        val requests: Int)

    suspend fun run(accessToken: String): Result {
        val counter = CountingTransport(UrlConnectionCloudTransport())
        val api = OneDriveRestApi(counter)
        var backups = 0
        var page: String? = null
        var pages = 0
        do {
            check(++pages <= 100) { "TOO_MANY_PAGES" }
            val result = api.listPage(accessToken, folderId, page)
            backups += result.files.size
            page = result.nextPageToken
        } while (page != null)
        return Result(counter.directories, counter.counted, counter.emptied, counter.asked, backups, counter.requests)
    }

    private class CountingTransport(private val real: CloudHttpTransport) : CloudHttpTransport {
        var directories = 0
        var counted = 0
        var emptied = 0
        var asked = 0
        var requests = 0

        override suspend fun execute(request: CloudHttpRequest, output: OutputStream?): CloudHttpResponse {
            check(request.method == "GET") { "READ_ONLY_PROBE" }
            requests++
            val path = URI(request.url).path.orEmpty()
            if (path.endsWith(":/snapshot.val_backup")) asked++
            val response = real.execute(request, output)
            val listing = path.endsWith("/children") && output == null
            if (listing && response.status in 200..299) runCatching {
                val items = JSONObject(response.body.toString(Charsets.UTF_8)).getJSONArray("value")
                for (index in 0 until items.length()) {
                    val item = items.getJSONObject(index)
                    if (!item.has("folder")) continue
                    directories++
                    val count = item.getJSONObject("folder").optInt("childCount", -1)
                    if (count >= 0) counted++
                    if (count == 0) emptied++
                }
            }
            return response
        }
    }
}
