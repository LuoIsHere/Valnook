package dev.valnook.data.cloud

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.domain.cloud.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OneDriveRestApiTest {
    @Test fun profile_displays_mail_with_safe_fallbacks_for_optional_fields() = runBlocking {
        val cases = listOf(
            """{"displayName":"Alex","mail":"alex@example.com","userPrincipalName":"login@example.com"}""" to "Alex · alex@example.com",
            """{"displayName":"Alex","mail":null,"userPrincipalName":"alex@example.com"}""" to "Alex · alex@example.com",
            """{"displayName":"Alex","mail":" ","userPrincipalName":"guest_example.com#EXT#@tenant.example"}""" to "Alex",
            """{"displayName":"Alex"}""" to "Alex",
            """{"displayName":null,"mail":"alex@example.com"}""" to "alex@example.com",
            """{"displayName":"alex@example.com","mail":"alex@example.com"}""" to "alex@example.com",
            """{"displayName":null,"mail":null,"userPrincipalName":null}""" to "Microsoft account"
        )
        for ((profile, expected) in cases) {
            val api = OneDriveRestApi(CloudHttpTransport { request, _ ->
                assertTrue(request.url.endsWith("\$select=id,displayName,mail,userPrincipalName"))
                CloudHttpResponse(200, emptyMap(), JSONObject(profile).put("id", "synthetic").toString().toByteArray())
            })
            assertEquals(expected, api.currentUser("synthetic").accountDisplay)
        }
    }

    @Test fun chunked_upload_uses_sequential_ranges_and_never_sends_graph_bearer_to_upload_host() = runBlocking {
        val server = FakeGraph(); val api = OneDriveRestApi(server)
        val payload = ByteArray(7 * 1024 * 1024 + 17) { (it % 113).toByte() }
        val file = upload(api, api.resolveOrCreateFolder("synthetic").id, payload = payload)
        assertEquals(payload.size.toLong(), file.size)
        assertArrayEquals(payload, server.archive)
        val chunks = server.requests.filter { it.url == "https://upload.test/session" }
        assertEquals(3, chunks.size)
        assertEquals("bytes 0-3276799/${payload.size}", chunks.first().headers["Content-Range"])
        assertTrue(chunks.all { "Authorization" !in it.headers })
    }

    @Test fun coordinator_round_trip_preserves_data_and_disconnect_reconnect_finds_existing_backup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val database = dev.valnook.data.database.ValnookDatabase.inMemory(context)
        val target = dev.valnook.data.database.ValnookDatabase.inMemory(context)
        val clock = java.time.Clock.systemUTC()
        val info = dev.valnook.data.portability.AppBuildInfo("valnook", "0.0.9", 9, "20261007.1", "synthetic", 14)
        val engine = dev.valnook.data.portability.RoomPortabilityEngine(context, database, clock, info)
        val targetEngine = dev.valnook.data.portability.RoomPortabilityEngine(context, target, clock, info)
        val server = FakeGraph()
        var cancelled = 0
        val scheduler = object : BackupScheduler {
            override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String,
                dataGeneration: Long, connectionGeneration: Long, scheduleGeneration: Long) = Unit
            override suspend fun cancel() { cancelled++ }
        }
        val coordinator = CloudBackupCoordinator(context, database, engine,
            CloudAccessProvider { CloudAccessResult.Granted("synthetic") }, OneDriveRestApi(server), scheduler,
            object : NetworkUtcClock { override suspend fun nowUtcMs() = clock.millis() }, NetworkAvailability { true })
        try {
            val commands = dev.valnook.data.transaction.RoomFinancialCommands(database, clock)
            commands.execute(dev.valnook.domain.repository.SaveAccount(UUID.randomUUID().toString(), null, null,
                "Synthetic bank", "local-only", listOf(dev.valnook.domain.repository.CashBalanceChange("CNY", -12345, null))))
            coordinator.connect(CloudAuthorizationGrant("synthetic", CloudProvider.ONEDRIVE, "synthetic-msal-account"))
            server.profile.put("mail", "synthetic@example.com")
            val connectionBefore = database.cloudBackup().state()
            coordinator.refresh()
            assertEquals("Synthetic Microsoft account · synthetic@example.com", coordinator.observeState().first().accountDisplay)
            assertEquals(connectionBefore.connection_generation, database.cloudBackup().state().connection_generation)
            assertEquals(connectionBefore.account_reference, database.cloudBackup().state().account_reference)
            coordinator.manualBackup()
            val state = coordinator.observeState().first()
            assertEquals(BackupAttemptState.SUCCEEDED, state.attemptState)
            val descriptor = state.backups.single()
            assertEquals("verified", descriptor.verificationState)
            val download = coordinator.stageForRestore(descriptor.fileId)
            val staged = coordinator.openStagedRestore(download.localId).use { targetEngine.prepareRestore(it, download.fileName) {} }
            try { targetEngine.commitRestore(staged) {} } finally { targetEngine.close(staged); coordinator.releaseStagedRestore(download.localId) }
            assertEquals(dev.valnook.data.repository.RoomOverview(database).snapshot(), dev.valnook.data.repository.RoomOverview(target).snapshot())
            assertNull(target.cloudBackup().state().account_reference)
            coordinator.disconnect()
            assertFalse(coordinator.observeState().first().connected)
            assertTrue(cancelled > 0)
            coordinator.connect(CloudAuthorizationGrant("synthetic", CloudProvider.ONEDRIVE, "synthetic-msal-account"))
            assertEquals(descriptor.fileId, coordinator.observeState().first().backups.single().fileId)
            assertFalse(coordinator.observeState().first().automaticEnabled)
        } finally { database.close(); target.close() }
    }

    @Test fun upload_verify_list_download_and_delete_with_only_app_folder_requests() = runBlocking {
        val server = FakeGraph()
        val api = OneDriveRestApi(server)
        assertEquals("user", api.currentUser("synthetic").accountReference)
        val root = api.resolveOrCreateFolder("synthetic")
        assertEquals("drive|root", root.id)
        val file = upload(api, root.id)
        assertEquals("uploaded-unverified", file.properties["verificationState"])
        api.verify("synthetic", file, file.properties.getValue("archiveSha256"), file.size)
        val verified = api.markVerified("synthetic", file.id, file.properties + ("verificationState" to "verified")) {}
        assertEquals("verified", verified.properties["verificationState"])
        assertEquals(file.id, api.listPage("synthetic", root.id, null).files.single().id)
        val output = ByteArrayOutputStream()
        api.download("synthetic", file.id, output)
        assertArrayEquals(PAYLOAD, output.toByteArray())
        api.trash("synthetic", file.id) {}
        assertTrue(server.deleted)
        assertTrue(server.requests.filter { !it.url.startsWith(GRAPH) }.all { "Authorization" !in it.headers })
        assertTrue(server.requests.filter { it.url.startsWith(GRAPH) }.all { it.headers["Authorization"] == "Bearer synthetic" })
    }

    @Test fun changed_remote_version_invalidates_verified_marker_and_corruption_is_rejected() = runBlocking {
        val server = FakeGraph(); val api = OneDriveRestApi(server)
        val file = upload(api, api.resolveOrCreateFolder("synthetic").id)
        api.markVerified("synthetic", file.id, file.properties + ("verificationState" to "verified")) {}
        server.version = "changed"
        server.archive = byteArrayOf(9, 9)
        assertEquals("uploaded-unverified", api.metadata("synthetic", file.id).properties["verificationState"])
        expectCloud(CloudBackupError.VERIFY_FAILED) { api.verify("synthetic", file, file.properties.getValue("archiveSha256"), file.size) }
    }

    @Test fun late_disconnect_guard_prevents_remote_writes() = runBlocking {
        val server = FakeGraph(); val api = OneDriveRestApi(server)
        val root = api.resolveOrCreateFolder("synthetic")
        val mutations = server.requests.count { it.method != "GET" }
        expectCloud(CloudBackupError.SESSION_EXPIRED) {
            upload(api, root.id) { throw CloudBackupException(CloudBackupError.SESSION_EXPIRED) }
        }
        assertEquals(mutations, server.requests.count { it.method != "GET" })
    }

    @Test fun unknown_final_upload_response_remains_discoverable_by_attempt_id() = runBlocking {
        val server = FakeGraph(); val api = OneDriveRestApi(server)
        val root = api.resolveOrCreateFolder("synthetic")
        server.loseFinalResponse = true
        try { upload(api, root.id); fail("Expected unknown result") }
        catch (error: DriveRequestException) { assertTrue(error.outcomeUnknown) }
        val found = api.listPage("synthetic", root.id, null).files.single()
        assertEquals("synthetic-attempt", found.properties["attemptId"])
        val verified = api.markVerified("synthetic", found.id, found.properties + ("verificationState" to "verified")) {}
        assertEquals("verified", verified.properties["verificationState"])
    }

    @Test fun retention_refuses_directory_containing_unrelated_user_file() = runBlocking {
        val server = FakeGraph(); val api = OneDriveRestApi(server)
        val file = upload(api, api.resolveOrCreateFolder("synthetic").id)
        api.markVerified("synthetic", file.id, file.properties + ("verificationState" to "verified")) {}
        server.unrelatedFile = true
        expectCloud(CloudBackupError.DRIVE_PERMISSION) { api.trash("synthetic", file.id) {} }
        assertFalse(server.deleted)
    }

    @Test fun rate_limit_retries_are_bounded_and_respect_retry_after() = runBlocking {
        var calls = 0; val delays = mutableListOf<Long>()
        val api = OneDriveRestApi(CloudHttpTransport { _, _ -> calls++; CloudHttpResponse(429, mapOf("Retry-After" to "2"), byteArrayOf()) }, { delays += it })
        try { api.currentUser("synthetic"); fail("Expected rate limit") }
        catch (error: DriveRequestException) { assertEquals(429, error.statusCode) }
        assertEquals(3, calls); assertEquals(listOf(2000L, 2000L), delays)
    }

    @Test fun hostile_next_link_is_rejected_without_sending_bearer() = runBlocking {
        var calls = 0
        val api = OneDriveRestApi(CloudHttpTransport { _, _ -> calls++; error("Must not send request") })
        expectCloud(CloudBackupError.DRIVE_PERMISSION) { api.listPage("synthetic", "drive|root", "https://example.com/steal") }
        assertEquals(0, calls)
    }

    @Test fun auth_permission_quota_errors_do_not_retry_or_expose_response_body() = runBlocking {
        for (status in listOf(401, 403, 404, 507)) {
            var calls = 0
            val api = OneDriveRestApi(CloudHttpTransport { _, _ -> calls++; CloudHttpResponse(status, emptyMap(), "private-response".toByteArray()) })
            try { api.currentUser("synthetic"); fail("Expected failure") }
            catch (error: DriveRequestException) { assertEquals(status, error.statusCode); assertFalse(error.toString().contains("private-response")) }
            assertEquals(1, calls)
        }
    }

    @Test fun secret_bearing_models_are_redacted() {
        assertFalse(CloudAuthorizationGrant("secret").toString().contains("secret"))
        assertFalse(CloudAccessResult.Granted("secret").toString().contains("secret"))
        assertFalse(CloudHttpRequest("GET", "https://host/secret", mapOf("Authorization" to "secret")).toString().contains("secret"))
    }

    private suspend fun upload(api: OneDriveRestApi, root: String, payload: ByteArray = PAYLOAD,
        guard: suspend () -> Unit = {}): RemoteBackupFile {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("onedrive-synthetic", ".val_backup", context.cacheDir)
        return try {
            file.writeBytes(payload)
            api.upload("synthetic", root, file, "Valnook_synthetic.val_backup", mapOf(
                "app" to "valnook", "role" to "backup", "backupId" to UUID.randomUUID().toString(),
                "attemptId" to "synthetic-attempt", "verificationState" to "uploaded-unverified",
                "archiveSha256" to MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            ), guard)
        } finally { file.delete() }
    }

    private suspend fun expectCloud(error: CloudBackupError, action: suspend () -> Unit) {
        try { action(); fail("Expected $error") } catch (actual: CloudBackupException) { assertEquals(error, actual.error) }
    }

    private class FakeGraph : CloudHttpTransport {
        val profile = JSONObject().put("id", "user").put("displayName", "Synthetic Microsoft account")
        val requests = mutableListOf<CloudHttpRequest>()
        var rootExists = false
        var backupName: String? = null
        var meta = byteArrayOf()
        var archive = byteArrayOf()
        var version = "v1"
        var deleted = false
        var loseFinalResponse = false
        var unrelatedFile = false
        override suspend fun execute(request: CloudHttpRequest, output: OutputStream?): CloudHttpResponse {
            requests += request
            val path = URI(request.url).path
            fun response(status: Int = 200, value: JSONObject = JSONObject(), headers: Map<String, String> = emptyMap()) =
                CloudHttpResponse(status, headers, value.toString().toByteArray())
            fun item(id: String, name: String, parent: String, folder: Boolean = false): JSONObject = JSONObject()
                .put("id", id).put("name", name).put("parentReference", JSONObject().put("id", parent).put("driveId", "drive"))
                .put("createdDateTime", "2026-10-07T00:00:00Z").put("eTag", version).put("size", archive.size)
                .also { if (folder) it.put("folder", JSONObject()) }
            val base = "/v1.0/drives/drive/items/"
            return when {
                path == "/v1.0/me" -> response(value = profile)
                path == "/v1.0/me/drive/special/approot" -> response(value = item("app", "Valnook", "top", true))
                path == "${base}app:/Valnook_backup" -> if (rootExists) response(value = item("root", "Valnook_backup", "app", true)) else response(404)
                path == "${base}app/children" -> { rootExists = true; response(201, item("root", "Valnook_backup", "app", true)) }
                path.startsWith("${base}root:/") -> response(404)
                path == "${base}root/children" && request.method == "POST" -> {
                    backupName = JSONObject(request.body!!.toString(Charsets.UTF_8)).getString("name")
                    response(201, item("backup", backupName!!, "root", true))
                }
                path == "${base}root/children" -> response(value = JSONObject().put("value", JSONArray().also {
                    if (!deleted && backupName != null) it.put(item("backup", backupName!!, "root", true))
                }))
                path == "${base}backup:/metadata.json:/content" && request.method == "PUT" -> { meta = request.body!!; response(201) }
                path == "${base}backup:/metadata.json:/content" -> {
                    output!!.write(meta); CloudHttpResponse(200, emptyMap(), byteArrayOf())
                }
                path.endsWith("/createUploadSession") -> response(value = JSONObject().put("uploadUrl", "https://upload.test/session"))
                request.url == "https://upload.test/session" -> {
                    archive += request.body!!
                    val total = request.headers.getValue("Content-Range").substringAfter('/').toLong()
                    if (archive.size < total) return response(202, JSONObject().put("nextExpectedRanges", JSONArray().put("${archive.size}-")))
                    if (loseFinalResponse) throw java.io.IOException("synthetic connection loss")
                    response(201, item("archive", "snapshot.val_backup", "backup"))
                }
                path == "${base}archive" && request.method == "DELETE" -> { assertEquals(version, request.headers["If-Match"]); deleted = true; response(204) }
                path == "${base}meta" && request.method == "DELETE" -> response(204)
                path == "${base}backup:/snapshot.val_backup" || path == "${base}archive" -> response(value = item("archive", "snapshot.val_backup", "backup"))
                path == "${base}archive/content" -> response(302, headers = mapOf("Location" to "https://download.test/archive"))
                request.url == "https://download.test/archive" -> { output!!.write(archive); CloudHttpResponse(200, emptyMap(), byteArrayOf()) }
                path == "${base}backup/children" -> response(value = JSONObject().put("value", JSONArray()
                    .put(item("archive", "snapshot.val_backup", "backup")).put(item("meta", "metadata.json", "backup"))
                    .also { if (unrelatedFile) it.put(item("user", "personal.txt", "backup")) }))
                path == "${base}backup" && request.method == "DELETE" -> { assertEquals(version, request.headers["If-Match"]); deleted = true; response(204) }
                path == "${base}backup" -> response(value = item("backup", backupName!!, "root", true))
                else -> error("Unexpected synthetic request: ${request.method} $path")
            }
        }
    }

    private companion object {
        const val GRAPH = "https://graph.microsoft.com"
        val PAYLOAD = ByteArray(1024) { (it % 127).toByte() }
    }
}
