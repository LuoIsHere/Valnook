package dev.valnook.data.cloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The picture folder on OneDrive: claimed only when it is Valnook's, never replacing a picture. */
@RunWith(AndroidJUnit4::class)
class OneDriveImagePoolTest {
    private val key = "c".repeat(64)

    /** Items by id under one backup folder ("root"), answering the Graph calls the picture folder makes. */
    private class Graph : CloudHttpTransport {
        data class Item(val id: String, val name: String, val parent: String, val folder: Boolean, var bytes: ByteArray = ByteArray(0))
        val items = linkedMapOf("root" to Item("root", "Valnook_backup", "", true))
        val calls = mutableListOf<String>()
        private var next = 0
        fun add(parent: String, name: String, folder: Boolean, bytes: ByteArray = ByteArray(0)) =
            Item("n${next++}", name, parent, folder, bytes).also { items[it.id] = it }
        fun child(parent: String, name: String) = items.values.firstOrNull { it.parent == parent && it.name == name }
        private fun json(item: Item) = JSONObject().put("id", item.id).put("name", item.name).put("eTag", "e-${item.id}")
            .put("createdDateTime", "2026-10-03T06:00:00Z").put("size", item.bytes.size).apply {
                if (item.folder) put("folder", JSONObject().put("childCount", items.values.count { it.parent == item.id }))
                else put("file", JSONObject().put("hashes", JSONObject().put("quickXorHash", QuickXorHash.of(item.bytes))))
            }
        private fun ok(body: JSONObject, status: Int = 200) = CloudHttpResponse(status, emptyMap(), body.toString().toByteArray())
        private fun missing() = CloudHttpResponse(404, emptyMap(), ByteArray(0))

        override suspend fun execute(request: CloudHttpRequest, output: java.io.OutputStream?): CloudHttpResponse {
            calls += "${request.method} ${request.url.substringAfter("/items/").substringBefore("?")}"
            val raw = URI(request.url).rawPath.substringAfter("/items/")
            val parts = raw.split(":/").map { URLDecoder.decode(it, "UTF-8") }
            val id = parts[0].substringBefore("/")
            val children = parts.size == 1 && parts[0].endsWith("/children")
            // A path may go down more than one folder, as refs/<backup>.json does.
            val target = if (parts.size >= 2) parts[1].split("/").fold(items[id]) { at, name -> at?.let { child(it.id, name) } }
                else items[id]
            val content = parts.size == 3 && parts[2] == "content"
            return when {
                request.method == "GET" && children -> ok(JSONObject().put("value",
                    JSONArray(items.values.filter { it.parent == id }.map(::json))))
                request.method == "POST" && parts[0].endsWith("/children") -> {
                    val name = JSONObject(String(request.body!!)).getString("name")
                    if (child(id, name) != null) CloudHttpResponse(409, emptyMap(), ByteArray(0)) else ok(json(add(id, name, true)), 201)
                }
                request.method == "PUT" && content -> {
                    if (target != null && request.url.contains("conflictBehavior=fail")) CloudHttpResponse(409, emptyMap(), ByteArray(0))
                    else ok(json((target ?: add(id, parts[1], false)).also { it.bytes = request.body!! }), 201)
                }
                request.method == "GET" && content -> target?.let { output!!.write(it.bytes); CloudHttpResponse(200, emptyMap(), ByteArray(0)) } ?: missing()
                request.method == "GET" -> target?.let { ok(json(it)) } ?: missing()
                request.method == "DELETE" -> { items.remove(id); CloudHttpResponse(204, emptyMap(), ByteArray(0)) }
                else -> error("Unexpected ${request.method} $raw")
            }
        }
    }

    @Test fun a_missing_folder_is_made_with_its_notice_and_then_claimed_without_listing() = runBlocking {
        val graph = Graph(); val api = OneDriveRestApi(graph)
        val folder = api.claimImageFolder("synthetic", "drive|root") {}
        val made = graph.child("root", ImagePool.FOLDER)!!
        assertEquals("drive|${made.id}", folder)
        assertTrue(ImagePool.isNotice(String(graph.child(made.id, ImagePool.NOTICE)!!.bytes)))
        graph.calls.clear()
        assertEquals(folder, api.claimImageFolder("synthetic", "drive|root") {})
        assertTrue(graph.calls.none { it.contains("/children") })
    }

    @Test fun a_folder_with_someone_elses_files_or_notice_is_never_claimed() = runBlocking {
        val graph = Graph(); val api = OneDriveRestApi(graph)
        val other = graph.add("root", ImagePool.FOLDER, true)
        graph.add(other.id, "holiday.jpg", false, byteArrayOf(1))
        assertThrows(ImageFolderConflict::class.java) { runBlocking { api.claimImageFolder("synthetic", "drive|root") {} } }
        assertNull(api.findImageFolder("synthetic", "drive|root"))
        assertNull(graph.child(other.id, ImagePool.NOTICE))
        // A notice that is not Valnook's says the same.
        val second = Graph(); val secondApi = OneDriveRestApi(second)
        val folder = second.add("root", ImagePool.FOLDER, true)
        second.add(folder.id, ImagePool.NOTICE, false, "My own notes".toByteArray())
        assertThrows(ImageFolderConflict::class.java) { runBlocking { secondApi.claimImageFolder("synthetic", "drive|root") {} } }
        // A file where the folder should be, too.
        val third = Graph(); third.add("root", ImagePool.FOLDER, false, byteArrayOf(1))
        assertThrows(ImageFolderConflict::class.java) { runBlocking { OneDriveRestApi(third).claimImageFolder("synthetic", "drive|root") {} } }
        Unit
    }

    @Test fun valnooks_folder_without_its_notice_is_claimed_again_and_gets_it_back() = runBlocking {
        val graph = Graph(); val api = OneDriveRestApi(graph)
        val folder = graph.add("root", ImagePool.FOLDER, true)
        graph.add(folder.id, ImagePool.pictureName(key), false, byteArrayOf(1, 2))
        val lists = graph.add(folder.id, ImagePool.LISTS, true)
        graph.add(lists.id, "${java.util.UUID.randomUUID()}.json", false, byteArrayOf(1))
        assertEquals("drive|${folder.id}", api.findImageFolder("synthetic", "drive|root"))
        assertNull(graph.child(folder.id, ImagePool.NOTICE))
        assertEquals("drive|${folder.id}", api.claimImageFolder("synthetic", "drive|root") {})
        assertNotNull(graph.child(folder.id, ImagePool.NOTICE))
    }

    @Test fun pictures_go_up_once_and_lists_round_trip() = runBlocking {
        val graph = Graph(); val api = OneDriveRestApi(graph)
        val folder = api.claimImageFolder("synthetic", "drive|root") {}
        val bytes = byteArrayOf(5, 6, 7)
        val first = api.uploadImage("synthetic", folder, ImagePool.pictureName(key), bytes) {}
        assertEquals(QuickXorHash.of(bytes), first.quickXor)
        // The same name again is the very same picture: the one there is returned, not replaced.
        val again = api.uploadImage("synthetic", folder, ImagePool.pictureName(key), byteArrayOf(9)) {}
        assertEquals(first.id, again.id)
        assertArrayEquals(bytes, graph.items.values.single { it.name == ImagePool.pictureName(key) }.bytes)
        assertEquals(listOf(ImagePool.pictureName(key)), api.listImages("synthetic", folder, null).files.map { it.name })
        val backup = java.util.UUID.randomUUID().toString()
        assertNull(api.readImageList("synthetic", folder, backup))
        api.writeImageList("synthetic", folder, backup, listOf(key)) {}
        assertEquals(setOf(key), api.readImageList("synthetic", folder, backup))
        assertEquals(listOf("$backup.json"), api.listImageLists("synthetic", folder, null).files.map { it.name })
        val out = java.io.ByteArrayOutputStream()
        api.downloadImage("synthetic", folder, ImagePool.pictureName(key), out)
        assertArrayEquals(bytes, out.toByteArray())
    }
}
