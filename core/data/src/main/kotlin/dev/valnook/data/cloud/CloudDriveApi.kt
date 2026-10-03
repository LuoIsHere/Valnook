package dev.valnook.data.cloud

import java.io.File
import java.io.OutputStream

internal data class DriveFolder(val id: String, val name: String)

internal data class DriveUser(
    val accountReference: String,
    val accountDisplay: String
)

internal data class DriveFile(
    val id: String,
    val name: String,
    val createdTime: String,
    val size: Long,
    val md5Checksum: String?,
    val parents: List<String>,
    val appProperties: Map<String, String>,
    val trashed: Boolean
)

internal data class DrivePage(val files: List<DriveFile>, val nextPageToken: String?)

internal class DriveRequestException(
    val statusCode: Int?,
    val outcomeUnknown: Boolean,
    cause: Throwable? = null
) : IllegalStateException("Drive request failed", cause)

internal interface CloudDriveApi {
    /** UTC from the authenticated Drive HTTPS response. No account or file data is requested. */
    suspend fun serverUtcMs(accessToken: String): Long?
    suspend fun currentUser(accessToken: String): DriveUser
    suspend fun resolveOrCreateFolder(accessToken: String): DriveFolder
    suspend fun upload(
        accessToken: String,
        folderId: String,
        localFile: File,
        fileName: String,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit
    ): DriveFile
    suspend fun metadata(accessToken: String, fileId: String): DriveFile
    suspend fun markVerified(
        accessToken: String,
        fileId: String,
        appProperties: Map<String, String>,
        beforeRemoteSideEffect: suspend () -> Unit
    ): DriveFile
    suspend fun listPage(accessToken: String, folderId: String, pageToken: String?): DrivePage
    suspend fun trash(accessToken: String, fileId: String, beforeRemoteSideEffect: suspend () -> Unit)
    suspend fun download(accessToken: String, fileId: String, output: OutputStream)
}
