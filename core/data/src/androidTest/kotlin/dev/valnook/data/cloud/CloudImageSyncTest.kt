package dev.valnook.data.cloud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.image.BillImageStore
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.cloud.*
import dev.valnook.domain.model.BillDraft
import dev.valnook.domain.model.BillImage
import dev.valnook.domain.model.BillType
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.DeleteBill
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveBill
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Bill pictures go to OneDrive's picture folder after the data, come back on demand, and go when nothing holds them. */
@RunWith(AndroidJUnit4::class)
class CloudImageSyncTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val start = Instant.parse("2026-10-03T06:00:00Z")
    private val clock = Clock.fixed(start, ZoneOffset.UTC)
    private lateinit var database: ValnookDatabase
    private lateinit var dir: File
    private lateinit var store: BillImageStore
    private lateinit var commands: RoomFinancialCommands
    private lateinit var drive: PictureDrive
    private lateinit var coordinator: CloudBackupCoordinator
    private val time = object : NetworkUtcClock { var now = start.toEpochMilli(); override suspend fun nowUtcMs(): Long = now }
    private var cash = 0L

    @Before fun prepare() = runBlocking {
        context.getSharedPreferences("valnook_cloud_images", 0).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        dir = File(context.cacheDir, "cloud-pictures-${UUID.randomUUID()}")
        store = BillImageStore(dir, database, clock)
        commands = RoomFinancialCommands(database, clock, store)
        drive = PictureDrive()
        val portability = RoomPortabilityEngine(context, database, clock,
            AppBuildInfo("valnook", "0.0.19", 1904, "20261114.1", "20261114.1", 27))
        coordinator = CloudBackupCoordinator(context, database, portability,
            CloudAccessProvider { CloudAccessResult.Granted("synthetic-access-token") }, drive,
            object : BackupScheduler {
                override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String, dataGeneration: Long,
                    connectionGeneration: Long, scheduleGeneration: Long) = Unit
                override suspend fun cancel() = Unit
            }, time, object : NetworkAvailability { override fun connected() = true }, store)
        val bank = commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null, "Synthetic Bank", "",
            listOf(CashBalanceChange("CNY", 100_000, null, name = "A")))).id
        cash = RoomOverview(database).snapshot().cash.single { it.account_id == bank }.id
        coordinator.connect(CloudAuthorizationGrant("synthetic-access-token"))
        coordinator.acknowledgeImageNotice()
    }

    @After fun close() { database.close(); dir.deleteRecursively() }

    private fun picture(shade: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE); drawRect(10f, 10f, 190f, 40f + shade, Paint().apply { color = Color.rgb(shade, 70, 140) }) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, it); bitmap.recycle() }.toByteArray()
    }
    private suspend fun stored(vararg shades: Int): List<BillImage> = shades.map { store.put(picture(it)) }.also { list -> store.unpin(list.map { it.key }) }
    private suspend fun bill(images: List<BillImage>): Long {
        val food = database.openHelper.readableDatabase.query("SELECT id FROM bill_categories WHERE builtin_key='food'")
            .use { it.moveToFirst(); it.getLong(0) }
        return commands.execute(SaveBill(UUID.randomUUID().toString(), null, null, BillDraft(BillType.EXPENSE, clock.millis(), 1_000,
            "CNY", 0, cash, 1_000, categoryId = food, note = "Synthetic", images = images))).id
    }
    private suspend fun status() = coordinator.observeImages().first()
    private suspend fun lastBackupId() = drive.files.filter { !it.trashed }.maxBy { it.id.removePrefix("new-").toIntOrNull() ?: 0 }
        .properties.getValue("backupId")

    @Test fun the_first_backup_makes_the_folder_lists_the_pictures_and_uploads_them_after_the_data() = runBlocking {
        val pictures = stored(10, 80)
        bill(pictures)
        coordinator.manualBackup()
        assertEquals(BackupAttemptState.SUCCEEDED, coordinator.observeState().first().attemptState)
        assertTrue(drive.noticeWritten)
        assertEquals(pictures.map { it.key }.toSet(), drive.lists.getValue(lastBackupId()).first)
        pictures.forEach { assertArrayEquals(store.file(it.key).readBytes(), drive.pictures.getValue(ImagePool.pictureName(it.key)).first) }
        // The data went up before any picture.
        assertEquals("snapshot", drive.order.first())
        assertEquals(CloudImageStatus(onDevice = 2), status())
        assertTrue(database.bills().imageFiles().all { it.cloud_size != null && it.cloud_quick_xor != null })
    }

    @Test fun pictures_wait_until_the_onedrive_notice_is_read_while_the_data_backs_up() = runBlocking {
        context.getSharedPreferences("valnook_cloud_images", 0).edit().clear().commit()
        val unread = CloudBackupCoordinator(context, database, RoomPortabilityEngine(context, database, clock,
            AppBuildInfo("valnook", "0.0.19", 1904, "20261114.1", "20261114.1", 27)),
            CloudAccessProvider { CloudAccessResult.Granted("synthetic-access-token") }, drive,
            object : BackupScheduler {
                override suspend fun replace(nextDueAtUtcMs: Long, referenceUtcMs: Long, cycleId: String, dataGeneration: Long,
                    connectionGeneration: Long, scheduleGeneration: Long) = Unit
                override suspend fun cancel() = Unit
            }, time, object : NetworkAvailability { override fun connected() = true }, store)
        bill(stored(10))
        assertTrue(unread.observeImages().first().noticeNeeded)
        unread.manualBackup()
        assertEquals(BackupAttemptState.SUCCEEDED, unread.observeState().first().attemptState)
        assertTrue(drive.pictures.isEmpty() && !drive.noticeWritten)
        unread.acknowledgeImageNotice()
        assertFalse(unread.observeImages().first().noticeNeeded)
        unread.manualBackup()
        assertEquals(1, drive.pictures.size)
    }

    @Test fun later_backups_send_only_new_pictures_and_carry_on_after_a_stop() = runBlocking {
        bill(stored(10))
        coordinator.manualBackup()
        assertEquals(1, drive.pictureUploads)
        bill(stored(40, 70))
        drive.failUploadNumber = 3
        coordinator.manualBackup()
        // The data is backed up all the same; one picture made it, one waits.
        assertEquals(BackupAttemptState.SUCCEEDED, coordinator.observeState().first().attemptState)
        assertEquals(CloudImageProblem.INCOMPLETE, status().problem)
        assertEquals(1, status().notUploaded)
        drive.failUploadNumber = null
        coordinator.manualBackup()
        assertEquals(4, drive.pictureUploads)
        assertEquals(CloudImageStatus(onDevice = 3), status())
    }

    @Test fun a_bill_images_folder_that_is_not_valnooks_is_left_alone_while_the_data_backs_up() = runBlocking {
        bill(stored(20))
        drive.foreignFolder = true
        coordinator.manualBackup()
        assertEquals(BackupAttemptState.SUCCEEDED, coordinator.observeState().first().attemptState)
        assertEquals(CloudImageProblem.FOLDER_CONFLICT, status().problem)
        assertTrue(drive.pictures.isEmpty() && drive.lists.isEmpty() && !drive.noticeWritten)
        assertEquals(1, status().notUploaded)
    }

    @Test fun a_picture_that_arrives_damaged_is_removed_from_onedrive_and_sent_again_next_time() = runBlocking {
        bill(stored(30))
        drive.wrongHash = true
        coordinator.manualBackup()
        assertEquals(CloudImageProblem.INCOMPLETE, status().problem)
        assertTrue(drive.pictures.isEmpty())
        drive.wrongHash = false
        coordinator.manualBackup()
        assertEquals(1, drive.pictures.size)
        assertNull(status().problem)
    }

    @Test fun pictures_nothing_holds_go_three_days_after_they_went_up_unless_a_kept_backup_lacks_its_list() = runBlocking {
        val kept = stored(50)
        bill(kept)
        drive.seedPicture("a".repeat(64), "2026-09-01T00:00:00Z")
        drive.seedPicture("b".repeat(64), "2026-10-03T05:00:00Z")
        // An old backup kept by rotation without a list: nothing may go.
        drive.seedVerified(UUID.randomUUID().toString(), withList = false)
        coordinator.manualBackup()
        assertTrue(drive.pictures.keys.containsAll(listOf("a".repeat(64) + ".webp", "b".repeat(64) + ".webp")))
        // Once every kept backup has its list, the picture none holds and that went up long ago goes; the recent one stays.
        drive.files.filter { it.properties["verificationState"] == "verified" && it.properties["backupId"]!! !in drive.lists.keys }
            .forEach { drive.lists[it.properties.getValue("backupId")] = emptySet<String>() to "2026-09-01T00:00:00Z" }
        coordinator.manualBackup()
        assertFalse(drive.pictures.containsKey("a".repeat(64) + ".webp"))
        assertTrue(drive.pictures.containsKey("b".repeat(64) + ".webp"))
        assertTrue(drive.pictures.containsKey(ImagePool.pictureName(kept.single().key)))
        // Three days on, the recent one goes too.
        time.now = Instant.parse("2026-10-07T00:00:00Z").toEpochMilli()
        coordinator.manualBackup()
        assertFalse(drive.pictures.containsKey("b".repeat(64) + ".webp"))
        assertTrue(drive.pictures.containsKey(ImagePool.pictureName(kept.single().key)))
    }

    @Test fun pictures_asked_to_go_leave_onedrive_at_the_next_backup_unless_a_bill_holds_them_again() = runBlocking {
        val (gone, held) = stored(60, 90)
        val bill = bill(listOf(gone))
        bill(listOf(held))
        coordinator.manualBackup()
        commands.execute(DeleteBill(UUID.randomUUID().toString(), bill, database.bills().bill(bill)!!.revision))
        listOf(gone, held).forEach { image ->
            database.bills().putImageFile(database.bills().imageFile(image.key)!!.copy(cloud_delete_requested_at_ms = time.now))
        }
        coordinator.manualBackup()
        assertFalse(drive.pictures.containsKey(ImagePool.pictureName(gone.key)))
        assertTrue(drive.pictures.containsKey(ImagePool.pictureName(held.key)))
        assertTrue(database.bills().imageFiles().none { it.cloud_delete_requested_at_ms != null })
    }

    @Test fun pictures_come_back_from_onedrive_checked_and_one_that_is_gone_counts_as_missing() = runBlocking {
        val (one, two, three) = stored(15, 45, 75)
        bill(listOf(one, two, three))
        coordinator.manualBackup()
        // Cleared from this device; one damaged in OneDrive, one gone from it.
        listOf(one, two, three).forEach { store.dropCorrupt(it.key) }
        assertEquals(3, status().onlyInCloud)
        drive.pictures[ImagePool.pictureName(two.key)] = picture(200) to "2026-10-03T06:00:00Z"
        drive.pictures.remove(ImagePool.pictureName(three.key))
        assertEquals(1, coordinator.fetchImages(listOf(one.key, two.key, three.key)))
        assertTrue(store.has(one.key) && !store.has(two.key) && !store.has(three.key))
        // The one that is gone is no longer "in OneDrive"; the damaged one still is and can be tried again.
        assertNull(database.bills().imageFile(three.key))
        assertEquals(1, status().onlyInCloud)
    }

    @Test fun after_a_restore_missing_pictures_come_back_newest_first_by_when_they_were_put_on_a_bill() = runBlocking {
        val old = stored(25)
        bill(old)
        database.openHelper.writableDatabase.execSQL("UPDATE bill_attachments SET created_at_ms=0")
        val recent = stored(85)
        bill(recent)
        coordinator.manualBackup()
        (old + recent).forEach { store.dropCorrupt(it.key) }
        database.bills().forgetCloudCopies(); database.bills().dropEmptyImageRecords()
        assertEquals(1, coordinator.fetchMissingImages(attachedSinceMs = clock.millis() - 1_000))
        assertTrue(store.has(recent.single().key) && !store.has(old.single().key))
        assertEquals(1, status().onlyInCloud)
        assertEquals(1, coordinator.fetchMissingImages(null))
        assertTrue(store.has(old.single().key))
    }

    @Test fun the_automatic_clean_up_lets_old_pictures_leave_this_device_once_onedrive_has_them() = runBlocking {
        val preferences = dev.valnook.data.image.BillImagePreferences.of(context)
        try {
            val (old, recent) = stored(12, 34)
            bill(listOf(old, recent))
            // The old one was saved here two months ago.
            database.bills().putImageFile(database.bills().imageFile(old.key)!!.copy(
                local_saved_at_ms = clock.millis() - 60L * 24 * 3600 * 1000))
            preferences.setAutoCleanMonths(1)
            coordinator.manualBackup()
            assertFalse(store.has(old.key))
            assertTrue(store.has(recent.key))
            assertEquals(1, status().onlyInCloud)
            // Fetched back, it starts its month again and stays through the next backup.
            assertEquals(1, coordinator.fetchImages(listOf(old.key)))
            coordinator.manualBackup()
            assertTrue(store.has(old.key))
        } finally {
            preferences.setAutoCleanMonths(null)
        }
    }

    @Test fun disconnecting_forgets_what_onedrive_holds() = runBlocking {
        bill(stored(35))
        coordinator.manualBackup()
        coordinator.disconnect()
        assertTrue(database.bills().imageFiles().all { it.cloud_size == null && it.cloud_quick_xor == null })
        assertEquals(1, status().notUploaded)
    }

    /** A drive with a picture folder kept in memory; files are named and checked as OneDrive would. */
    private inner class PictureDrive : CloudDriveApi {
        val files = mutableListOf<RemoteBackupFile>()
        private val contents = mutableMapOf<String, ByteArray>()
        val pictures = linkedMapOf<String, Pair<ByteArray, String>>()
        val lists = linkedMapOf<String, Pair<Set<String>, String>>()
        val order = mutableListOf<String>()
        var noticeWritten = false
        var foreignFolder = false
        var wrongHash = false
        var failUploadNumber: Int? = null
        var pictureUploads = 0
        private var uploads = 0
        private val folder = RemoteBackupFolder("managed-folder", "Valnook_backup")
        private fun now() = Instant.ofEpochMilli(time.now).toString()

        override suspend fun serverUtcMs(accessToken: String) = time.now
        override suspend fun currentUser(accessToken: String) = RemoteBackupUser("synthetic-account", "Test account")
        override suspend fun resolveOrCreateFolder(accessToken: String) = folder
        override suspend fun upload(accessToken: String, folderId: String, localFile: File, fileName: String,
            appProperties: Map<String, String>, beforeRemoteSideEffect: suspend () -> Unit): RemoteBackupFile {
            uploads++
            order += "snapshot"
            val id = "new-$uploads"
            contents[id] = localFile.readBytes()
            return RemoteBackupFile(id, fileName, now(), localFile.length(), null, listOf(folderId), appProperties, false)
                .also(files::add)
        }
        override suspend fun verify(accessToken: String, file: RemoteBackupFile, expectedSha256: String, expectedSize: Long) =
            verifyBytes(expectedSha256, expectedSize) { it.write(contents.getValue(file.id)) }
        override suspend fun metadata(accessToken: String, fileId: String) =
            files.firstOrNull { it.id == fileId } ?: throw DriveRequestException(404, false)
        override suspend fun markVerified(accessToken: String, verified: RemoteBackupFile, appProperties: Map<String, String>,
            beforeRemoteSideEffect: suspend () -> Unit): RemoteBackupFile {
            val index = files.indexOfFirst { it.id == verified.id }
            return files[index].copy(properties = appProperties).also { files[index] = it }
        }
        override suspend fun listPage(accessToken: String, folderId: String, pageToken: String?) =
            RemoteBackupPage(files.filter { !it.trashed }, null)
        override suspend fun trash(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit) {
            val index = files.indexOfFirst { it.id == fileId }
            files[index] = files[index].copy(trashed = true)
        }
        override suspend fun trashUnverified(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit) =
            trash(accessToken, fileId, beforeRemoteSideEffect)
        override suspend fun download(accessToken: String, fileId: String, output: OutputStream) = output.write(contents.getValue(fileId))

        override suspend fun claimImageFolder(accessToken: String, folderId: String, beforeRemoteSideEffect: suspend () -> Unit): String {
            if (foreignFolder) throw ImageFolderConflict()
            noticeWritten = true
            return "pictures"
        }
        override suspend fun findImageFolder(accessToken: String, folderId: String) = if (foreignFolder) null else "pictures"
        private fun entry(name: String, bytes: ByteArray, created: String) = RemoteImageFile("id-$name", name, bytes.size.toLong(),
            QuickXorHash.of(bytes).let { if (wrongHash) "x$it" else it }, created, "etag-$name")
        override suspend fun listImages(accessToken: String, imageFolder: String, pageToken: String?): RemoteImagePage {
            // Two to a page, as a real listing comes in pages.
            val all = pictures.entries.toList()
            val offset = pageToken?.toInt() ?: 0
            val page = all.drop(offset).take(2).map { (name, value) -> entry(name, value.first, value.second) }
            return RemoteImagePage(page, (offset + 2).takeIf { it < all.size }?.toString())
        }
        override suspend fun uploadImage(accessToken: String, imageFolder: String, name: String, bytes: ByteArray,
            beforeRemoteSideEffect: suspend () -> Unit): RemoteImageFile {
            pictureUploads++
            if (pictureUploads == failUploadNumber) throw DriveRequestException(503, false)
            order += "picture"
            pictures[name] = bytes to now()
            return entry(name, bytes, now())
        }
        override suspend fun downloadImage(accessToken: String, imageFolder: String, name: String, output: OutputStream) =
            output.write((pictures[name] ?: throw DriveRequestException(404, false)).first)
        override suspend fun deleteImageFile(accessToken: String, file: RemoteImageFile, beforeRemoteSideEffect: suspend () -> Unit) {
            pictures.remove(file.name)
            ImagePool.backupOf(file.name)?.let(lists::remove)
        }
        override suspend fun writeImageList(accessToken: String, imageFolder: String, backupId: String, keys: Collection<String>,
            beforeRemoteSideEffect: suspend () -> Unit) { lists[backupId] = keys.toSet() to now() }
        override suspend fun readImageList(accessToken: String, imageFolder: String, backupId: String) = lists[backupId]?.first
        override suspend fun listImageLists(accessToken: String, imageFolder: String, pageToken: String?) =
            RemoteImagePage(lists.map { (id, value) -> RemoteImageFile("list-$id", "$id.json", 10, null, value.second, null) }, null)

        fun seedPicture(key: String, created: String) { pictures["$key.webp"] = byteArrayOf(1, 2, 3) to created }
        fun seedVerified(backupId: String, withList: Boolean) {
            files += RemoteBackupFile("old-$backupId", "Valnook_old.val_backup", "2026-09-01T00:00:00Z", 100, null,
                listOf(folder.id), mapOf("app" to "valnook", "role" to "backup", "verificationState" to "verified",
                    "backupId" to backupId, "formatVersion" to "1", "dataSchemaVersion" to "13", "databaseSchemaVersion" to "27",
                    "appVersion" to "0.0.19", "internalBuildRevision" to "fixture", "snapshotCreatedAtUtc" to "2026-09-01T00:00:00Z"), false)
            if (withList) lists[backupId] = emptySet<String>() to "2026-09-01T00:00:00Z"
        }
    }
}
