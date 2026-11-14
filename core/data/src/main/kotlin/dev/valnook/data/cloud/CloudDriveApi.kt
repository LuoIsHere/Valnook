package dev.valnook.data.cloud

import java.io.File
import java.io.OutputStream

internal data class RemoteBackupFolder(val id: String, val name: String)

internal data class RemoteBackupUser(
    val accountReference: String,
    val accountDisplay: String
)

internal data class RemoteBackupFile(
    val id: String,
    val name: String,
    val createdTime: String,
    val size: Long,
    val md5Checksum: String?,
    val parents: List<String>,
    val properties: Map<String, String>,
    val trashed: Boolean,
    val version: String? = null
)

internal data class RemoteBackupPage(val files: List<RemoteBackupFile>, val nextPageToken: String?)

internal class DriveRequestException(
    val statusCode: Int?,
    val outcomeUnknown: Boolean,
    cause: Throwable? = null
) : IllegalStateException("Drive request failed", cause)

internal interface CloudDriveApi {
    val provider: dev.valnook.domain.cloud.CloudProvider get() = dev.valnook.domain.cloud.CloudProvider.GOOGLE_DRIVE
    /** Compare actual remote bytes, never trust an editable metadata verification flag. */
    suspend fun verify(accessToken: String, file: RemoteBackupFile, expectedSha256: String, expectedSize: Long) =
        verifyBytes(expectedSha256, expectedSize) { download(accessToken, file.id, it) }

    /** UTC from the authenticated Drive HTTPS response. No account or file data is requested. */
    suspend fun serverUtcMs(accessToken: String): Long?
    suspend fun currentUser(accessToken: String): RemoteBackupUser
    suspend fun resolveOrCreateFolder(accessToken: String): RemoteBackupFolder
    suspend fun upload(
        accessToken: String,
        folderId: String,
        localFile: File,
        fileName: String,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit
    ): RemoteBackupFile
    suspend fun metadata(accessToken: String, fileId: String): RemoteBackupFile
    /**
     * Publishes the verified marker for [verified], exactly as it was when its bytes were checked, and returns the
     * remote state after writing. It does not check the bytes again: call it only with a file that just passed [verify].
     */
    suspend fun markVerified(
        accessToken: String,
        verified: RemoteBackupFile,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit
    ): RemoteBackupFile
    suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): RemoteBackupPage
    suspend fun trash(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit)
    /** Like [trash], for this app's copy that never got the verified marker; a verified backup is refused. */
    suspend fun trashUnverified(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit)
    suspend fun download(accessToken: String, fileId: String, output: OutputStream)

    // Bill pictures (ImagePool). A drive without them returns null from the folder calls and is never asked for more.

    /**
     * Valnook's picture folder in the backup folder [folderId], made with its notice when there is none. Throws
     * [ImageFolderConflict] when a folder of that name is not Valnook's.
     */
    suspend fun claimImageFolder(accessToken: String, folderId: String, beforeRemoteSideEffect: suspend () -> Unit): String? = null
    /** The picture folder when it exists and is Valnook's; nothing is written. */
    suspend fun findImageFolder(accessToken: String, folderId: String): String? = null
    /** Pictures in the folder, one page at a time; other files are left out. */
    suspend fun listImages(accessToken: String, imageFolder: String, pageToken: String?): RemoteImagePage = unsupported()
    /** Stores a picture under [name]; one already there with that name is returned as it is. */
    suspend fun uploadImage(accessToken: String, imageFolder: String, name: String, bytes: ByteArray,
        beforeRemoteSideEffect: suspend () -> Unit): RemoteImageFile = unsupported()
    /** Writes picture [name] to [output]; a DriveRequestException with 404 when it is not there. */
    suspend fun downloadImage(accessToken: String, imageFolder: String, name: String, output: OutputStream): Unit = unsupported()
    /** Deletes a picture or a list, only as it was when listed. */
    suspend fun deleteImageFile(accessToken: String, file: RemoteImageFile, beforeRemoteSideEffect: suspend () -> Unit): Unit = unsupported()
    suspend fun writeImageList(accessToken: String, imageFolder: String, backupId: String, keys: Collection<String>,
        beforeRemoteSideEffect: suspend () -> Unit): Unit = unsupported()
    /** The pictures backup [backupId] holds, or null when its list is missing or not valid. */
    suspend fun readImageList(accessToken: String, imageFolder: String, backupId: String): Set<String>? = unsupported()
    suspend fun listImageLists(accessToken: String, imageFolder: String, pageToken: String?): RemoteImagePage = unsupported()
}

private fun unsupported(): Nothing = throw UnsupportedOperationException("This drive keeps no bill pictures")

/** Hashes what [download] writes and fails unless it is exactly [expectedSize] bytes with [expectedSha256]. */
internal suspend fun verifyBytes(expectedSha256: String, expectedSize: Long, download: suspend (OutputStream) -> Unit) {
    val hash = java.security.MessageDigest.getInstance("SHA-256")
    var count = 0L
    download(object : OutputStream() {
        override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            count += length
            if (count > expectedSize) throw dev.valnook.domain.cloud.CloudBackupException(dev.valnook.domain.cloud.CloudBackupError.VERIFY_FAILED)
            hash.update(bytes, offset, length)
        }
    })
    if (count != expectedSize || hash.digest().joinToString("") { "%02x".format(it) } != expectedSha256)
        throw dev.valnook.domain.cloud.CloudBackupException(dev.valnook.domain.cloud.CloudBackupError.VERIFY_FAILED)
}
