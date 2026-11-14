package dev.valnook.data.cloud

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/**
 * Debug-only check of the picture folder against the connected OneDrive: claims it, sends two made-up files, checks
 * OneDrive's own QuickXorHash against ours, reads them and a list back, and deletes what it made. Only pass/fail
 * flags and counts leave this class: no names, ids or response bodies.
 */
class LiveOneDriveImageProbe(private val folderId: String, private val fresh: Boolean = false) {
    data class Result(
        val claimed: Boolean,
        val claimedAgain: Boolean,
        val found: Boolean,
        val hashesMatch: Int,
        val sizesMatch: Int,
        val duplicateKeptFirst: Boolean,
        val listed: Int,
        val downloadsMatch: Int,
        val listRoundTrip: Boolean,
        val listListed: Boolean,
        val deleted: Int,
        val goneAfterDelete: Boolean,
        /** Pictures in the folder besides the probe's own: Valnook's real backups of bill pictures. */
        val otherPictures: Int = 0,
    )

    /** The step the probe reached, for a failure report; never a name, id or response body. */
    var step = "start"
        private set

    suspend fun run(accessToken: String): Result = try { probe(accessToken) } catch (error: DriveRequestException) {
        throw IllegalStateException("step=$step status=${error.statusCode}")
    } catch (error: Throwable) { throw IllegalStateException("step=$step ${error.javaClass.simpleName}") }

    private suspend fun probe(accessToken: String): Result {
        val api = OneDriveRestApi()
        if (fresh) {
            // Starts from no picture folder at all, to try the path that makes it.
            step = "remove-folder"
            val (drive, root) = folderId.split('|')
            val http = UrlConnectionCloudTransport()
            val base = "https://graph.microsoft.com/v1.0/drives/$drive/items/$root:/${ImagePool.FOLDER}"
            val auth = mapOf("Authorization" to "Bearer $accessToken", "Accept" to "application/json")
            val found = http.execute(CloudHttpRequest("GET", base, auth), null)
            if (found.status == 200) {
                val id = org.json.JSONObject(found.body.toString(Charsets.UTF_8)).getString("id")
                val gone = http.execute(CloudHttpRequest("DELETE", "https://graph.microsoft.com/v1.0/drives/$drive/items/$id", auth), null)
                check(gone.status in 200..299) { "remove ${gone.status}" }
            } else check(found.status == 404) { "look ${found.status}" }
        }
        step = "claim"
        val folder = api.claimImageFolder(accessToken, folderId) {}
        step = "claim-again"
        val claimedAgain = api.claimImageFolder(accessToken, folderId) {} == folder
        step = "find"
        val found = api.findImageFolder(accessToken, folderId) == folder
        // A small file and one spanning many 160-byte rounds of the hash.
        val random = Random(System.nanoTime())
        val files = listOf(random.nextBytes(3_000), random.nextBytes(40 * 1024 + 7))
        val names = files.map { ImagePool.pictureName(sha256(it)) }
        step = "upload"
        val uploaded = files.zip(names).map { (bytes, name) -> api.uploadImage(accessToken, folder, name, bytes) {} }
        val hashesMatch = uploaded.zip(files).count { (remote, bytes) -> remote.quickXor == QuickXorHash.of(bytes) }
        val sizesMatch = uploaded.zip(files).count { (remote, bytes) -> remote.size == bytes.size.toLong() }
        step = "upload-again"
        val again = api.uploadImage(accessToken, folder, names[0], byteArrayOf(1, 2, 3)) {}
        val duplicateKeptFirst = again.id == uploaded[0].id && again.size == files[0].size.toLong()
        step = "list"
        val listing = mutableListOf<RemoteImageFile>()
        var page: String? = null
        var pages = 0
        do {
            check(++pages <= 200) { "TOO_MANY_PAGES" }
            val result = api.listImages(accessToken, folder, page)
            listing += result.files
            page = result.nextPageToken
        } while (page != null)
        val listed = names.count { name -> listing.any { it.name == name } }
        step = "download"
        val downloadsMatch = files.zip(names).count { (bytes, name) ->
            val out = ByteArrayOutputStream()
            api.downloadImage(accessToken, folder, name, out)
            out.toByteArray().contentEquals(bytes)
        }
        val backupId = UUID.randomUUID().toString()
        val keys = names.map { it.removeSuffix(".webp") }.toSet()
        step = "write-list"
        api.writeImageList(accessToken, folder, backupId, keys) {}
        step = "read-list"
        val listRoundTrip = api.readImageList(accessToken, folder, backupId) == keys
        step = "list-lists"
        val lists = api.listImageLists(accessToken, folder, null).files
        val listFile = lists.firstOrNull { it.name == ImagePool.listName(backupId) }
        step = "delete"
        var deleted = 0
        uploaded.forEach { api.deleteImageFile(accessToken, it) {}; deleted++ }
        listFile?.let { api.deleteImageFile(accessToken, it) {}; deleted++ }
        step = "list-after"
        val after = api.listImages(accessToken, folder, null).files
        return Result(true, claimedAgain, found, hashesMatch, sizesMatch, duplicateKeptFirst, listed, downloadsMatch,
            listRoundTrip, listFile != null, deleted, names.none { name -> after.any { it.name == name } },
            listing.count { it.name !in names })
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
