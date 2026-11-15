package dev.valnook.data.ai

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Conversations live in a database of their own: the main database's version and backups never see them, and losing
 * them loses nothing that was saved. Versions move on by automatic migrations; one that cannot starts over ([open]
 * drops old tables). Version 2: messages remember whether they were shared from another app.
 */
@Database(entities = [AiConversationEntity::class, AiMessageEntity::class, AiPictureEntity::class,
    AiDraftEntity::class, AiUsageEntity::class], version = 2, exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)])
abstract class AiChatDatabase : RoomDatabase() {
    abstract fun chat(): AiChatDao

    companion object {
        const val FILE = "valnook_ai_chat.db"
        fun open(context: Context): AiChatDatabase = Room.databaseBuilder(context, AiChatDatabase::class.java, FILE)
            .fallbackToDestructiveMigration(dropAllTables = true).build()
        fun inMemory(context: Context): AiChatDatabase =
            Room.inMemoryDatabaseBuilder(context, AiChatDatabase::class.java).build()
    }
}

@Entity(tableName = "conversations", indices = [Index("last_at_ms")])
data class AiConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val created_at_ms: Long,
    val last_at_ms: Long,
    val tokens: Long,
)

/**
 * [kind] is USER, ASSISTANT or ERROR. An assistant message keeps [raw], the message exactly as the service returned
 * it, to send back unchanged; [text] is what the user reads.
 */
@Entity(tableName = "messages", indices = [Index("conversation_id")], foreignKeys = [ForeignKey(
    entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE)])
data class AiMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversation_id: Long,
    val kind: String,
    val text: String,
    val raw: String?,
    val reasoning: String?,
    val thinking_ms: Long?,
    val input_tokens: Long?,
    val output_tokens: Long?,
    val total_tokens: Long?,
    val cached_tokens: Long?,
    val calls: Int,
    val problem: String?,
    val no_drafts: Boolean,
    /** JSON array of ids of saved bills a reply tried to change. */
    val confirmed_bills: String,
    val created_at_ms: Long,
    /** A user message shared from another app: read with the draft tool required and thinking off. */
    @ColumnInfo(defaultValue = "0") val shared: Boolean = false,
)

/** A picture the user sent: its key in the bill picture library and a small copy that outlives it. */
@Entity(tableName = "message_pictures", primaryKeys = ["message_id", "position"], foreignKeys = [ForeignKey(
    entity = AiMessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE)])
data class AiPictureEntity(
    val message_id: Long,
    val position: Int,
    val image_key: String,
    /** JSON of the stored picture (key, size, width, height). */
    val image: String,
    val thumb: ByteArray,
) {
    override fun equals(other: Any?) = other is AiPictureEntity && other.message_id == message_id && other.position == position
    override fun hashCode() = (message_id * 31 + position).toInt()
}

/** A draft card. [payload] is the checked draft as JSON; [images] the pictures it takes along when saved. */
@Entity(tableName = "drafts", indices = [Index("conversation_id"), Index("message_id"), Index("state")], foreignKeys = [
    ForeignKey(entity = AiConversationEntity::class, parentColumns = ["id"], childColumns = ["conversation_id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = AiMessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE)])
data class AiDraftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversation_id: Long,
    val message_id: Long,
    val label: String,
    val payload: String,
    val state: String,
    val bill_id: Long?,
    val operation_id: String,
    val images: String,
    val keep_images: Boolean,
)

/** One round's tokens, from a conversation (CHAT) or a share (SHARE), for the month's usage. */
@Entity(tableName = "usage", indices = [Index("at_ms")])
data class AiUsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at_ms: Long,
    val source: String,
    val input_tokens: Long,
    val output_tokens: Long,
    val total_tokens: Long,
    val calls: Int,
)

data class AiUsageTotals(val requests: Int, val tokens: Long, val input: Long, val output: Long)

@Dao
interface AiChatDao {
    @Query("SELECT * FROM conversations ORDER BY last_at_ms DESC, id DESC")
    fun observeConversations(): Flow<List<AiConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY last_at_ms DESC, id DESC LIMIT 1")
    suspend fun latestConversation(): AiConversationEntity?

    @Query("SELECT * FROM conversations WHERE id=:id")
    suspend fun conversation(id: Long): AiConversationEntity?

    @Insert
    suspend fun insertConversation(value: AiConversationEntity): Long

    @Query("UPDATE conversations SET last_at_ms=:at, tokens=tokens+:tokens WHERE id=:id")
    suspend fun touch(id: Long, at: Long, tokens: Long)

    @Query("DELETE FROM conversations WHERE id=:id")
    suspend fun deleteConversation(id: Long)

    @Query("SELECT id FROM conversations WHERE last_at_ms<:before")
    suspend fun conversationsBefore(before: Long): List<Long>

    @Query("DELETE FROM conversations")
    suspend fun clearConversations()

    @Query("SELECT * FROM messages WHERE conversation_id=:conversationId ORDER BY id")
    fun observeMessages(conversationId: Long): Flow<List<AiMessageEntity>>

    @Query("SELECT * FROM messages WHERE conversation_id=:conversationId ORDER BY id")
    suspend fun messages(conversationId: Long): List<AiMessageEntity>

    @Insert
    suspend fun insertMessage(value: AiMessageEntity): Long

    @Query("UPDATE messages SET confirmed_bills=:ids WHERE id=:id")
    suspend fun setConfirmedBills(id: Long, ids: String)

    @Query("DELETE FROM messages WHERE id IN (:ids)")
    suspend fun deleteMessages(ids: List<Long>)

    @Query("SELECT * FROM message_pictures WHERE message_id IN (SELECT id FROM messages WHERE conversation_id=:conversationId) ORDER BY message_id, position")
    fun observePictures(conversationId: Long): Flow<List<AiPictureEntity>>

    @Query("SELECT * FROM message_pictures WHERE message_id=:messageId ORDER BY position")
    suspend fun pictures(messageId: Long): List<AiPictureEntity>

    @Insert
    suspend fun insertPictures(values: List<AiPictureEntity>)

    @Query("SELECT * FROM drafts WHERE conversation_id=:conversationId ORDER BY id")
    fun observeDrafts(conversationId: Long): Flow<List<AiDraftEntity>>

    @Query("SELECT * FROM drafts WHERE conversation_id=:conversationId ORDER BY id")
    suspend fun drafts(conversationId: Long): List<AiDraftEntity>

    @Query("SELECT * FROM drafts WHERE id=:id")
    suspend fun draft(id: Long): AiDraftEntity?

    @Query("SELECT * FROM drafts WHERE state='PENDING'")
    suspend fun pendingDrafts(): List<AiDraftEntity>

    @Query("SELECT * FROM drafts WHERE state='PENDING' AND conversation_id IN (:conversationIds)")
    suspend fun pendingDraftsIn(conversationIds: List<Long>): List<AiDraftEntity>

    /** One row per waiting draft: the conversation it is in. */
    @Query("SELECT conversation_id FROM drafts WHERE state='PENDING'")
    fun observePendingConversations(): Flow<List<Long>>

    @Query("SELECT EXISTS(SELECT 1 FROM drafts WHERE state='PENDING')")
    fun observeAnyPending(): Flow<Boolean>

    @Insert
    suspend fun insertDraft(value: AiDraftEntity): Long

    @Query("UPDATE drafts SET state=:state, bill_id=:billId, operation_id=:operationId WHERE id=:id")
    suspend fun setDraftState(id: Long, state: String, billId: Long?, operationId: String)

    @Query("UPDATE drafts SET keep_images=:keep WHERE id=:id")
    suspend fun setKeepImages(id: Long, keep: Boolean)

    @Insert
    suspend fun insertUsage(value: AiUsageEntity)

    @Query("""SELECT COALESCE(SUM(calls),0) AS requests, COALESCE(SUM(total_tokens),0) AS tokens, COALESCE(SUM(input_tokens),0) AS input,
        COALESCE(SUM(output_tokens),0) AS output FROM usage WHERE at_ms>=:from""")
    fun observeUsageSince(from: Long): Flow<AiUsageTotals>

    @Query("DELETE FROM usage WHERE at_ms<:before")
    suspend fun deleteUsageBefore(before: Long)

    @Query("DELETE FROM usage")
    suspend fun clearUsage()
}
