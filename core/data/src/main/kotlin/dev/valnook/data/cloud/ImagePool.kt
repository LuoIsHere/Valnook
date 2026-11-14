package dev.valnook.data.cloud

import dev.valnook.domain.model.BillImageRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Valnook's picture folder beside the backups: one file per picture named by its SHA-256, and in refs/ one list per
 * backup of the pictures it holds. A notice file in it says what it is and marks it as Valnook's.
 */
internal object ImagePool {
    const val FOLDER = "bill_images"
    const val LISTS = "refs"
    const val NOTICE = "README_DO_NOT_EDIT.txt"
    const val MARKER = "valnook-bill-images: 1"
    val NOTICE_TEXT = "此文件夹由 Valnook 自动管理，用来备份账单图片。请不要手动放入、修改、重命名或删除其中的文件，否则图片可能无法恢复。\n" +
        "This folder is managed by Valnook and holds backups of bill images. Do not add, change, rename or delete files here, " +
        "or images may not be restorable.\n$MARKER\n"
    /** Pictures this many days older than any list that holds them may go; a backup still on its way is never caught. */
    const val GRACE_MS = 3L * 24 * 60 * 60 * 1000
    /** A list is small; anything larger is not one of Valnook's. */
    const val MAX_LIST_BYTES = 2 * 1024 * 1024

    fun pictureName(key: String): String { require(BillImageRules.validKey(key)); return "$key.webp" }
    fun keyOf(name: String): String? = name.removeSuffix(".webp").takeIf { name.endsWith(".webp") && BillImageRules.validKey(it) }
    fun listName(backupId: String): String { require(UUID.fromString(backupId).toString() == backupId); return "$backupId.json" }
    fun backupOf(name: String): String? = name.removeSuffix(".json").takeIf {
        name.endsWith(".json") && runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false)
    }

    /** Whether [text] is Valnook's notice: its last line is the marker. */
    fun isNotice(text: String): Boolean = text.trimEnd().lines().lastOrNull()?.trim() == MARKER

    fun encodeList(backupId: String, keys: Collection<String>): ByteArray = JSONObject()
        .put("protocolVersion", 1).put("backupId", backupId).put("images", JSONArray(keys.sorted()))
        .toString().toByteArray(Charsets.UTF_8)

    /** The pictures a list names, or null when it is not a valid list for [backupId]. */
    fun decodeList(bytes: ByteArray, backupId: String): Set<String>? = runCatching {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        if (root.getInt("protocolVersion") != 1 || root.getString("backupId") != backupId) return null
        val array = root.getJSONArray("images")
        (0 until array.length()).map { array.getString(it) }.also { keys ->
            if (keys.any { !BillImageRules.validKey(it) }) return null
        }.toSet()
    }.getOrNull()
}

/** A file in the picture folder: a picture, or a backup's list. [id] is the drive's handle for it. */
internal data class RemoteImageFile(
    val id: String,
    val name: String,
    val size: Long,
    /** OneDrive's QuickXorHash of the content, when it reports one. */
    val quickXor: String?,
    val createdTime: String,
    val version: String?
)

internal data class RemoteImagePage(val files: List<RemoteImageFile>, val nextPageToken: String?)

/** A folder named bill_images is in the backup folder and is not Valnook's; it is never touched. */
internal class ImageFolderConflict : IllegalStateException("The picture folder is not Valnook's")
