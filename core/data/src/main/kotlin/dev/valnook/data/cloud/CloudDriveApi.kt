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
    suspend fun verify(accessToken: String, file: RemoteBackupFile, expectedSha256: String, expectedSize: Long) {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        var count = 0L
        download(accessToken, file.id, object : OutputStream() {
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
    suspend fun markVerified(
        accessToken: String,
        fileId: String,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit
    ): RemoteBackupFile
    suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): RemoteBackupPage
    suspend fun trash(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit)
    suspend fun download(accessToken: String, fileId: String, output: OutputStream)
}
