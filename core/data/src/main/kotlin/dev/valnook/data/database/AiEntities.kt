package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * The AI settings, one row (id 1) or none for the defaults. It travels with backups; the key, check results and
 * conversations stay on the device. Default ids are not foreign keys: an account or category deleted later falls back
 * to the next default when used.
 */
@Entity(tableName = "ai_settings")
data class AiSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val preset_id: String?,
    @ColumnInfo(defaultValue = "''") val base_url: String,
    @ColumnInfo(defaultValue = "''") val model: String,
    @ColumnInfo(defaultValue = "''") val extra_params: String,
    @ColumnInfo(defaultValue = "''") val thinking_off_params: String,
    @ColumnInfo(defaultValue = "''") val thinking_on_params: String,
    @ColumnInfo(defaultValue = "0") val enabled: Boolean,
    @ColumnInfo(defaultValue = "1") val bill_chat: Boolean,
    @ColumnInfo(defaultValue = "1") val share_text: Boolean,
    @ColumnInfo(defaultValue = "0") val share_image: Boolean,
    @ColumnInfo(defaultValue = "0") val query_enabled: Boolean,
    @ColumnInfo(defaultValue = "0") val thinking: Boolean,
    @ColumnInfo(defaultValue = "0") val chat: Boolean,
    @ColumnInfo(defaultValue = "0") val send_habits: Boolean,
    @ColumnInfo(defaultValue = "720") val default_time_minutes: Int,
    val default_cash_account_id: Long?,
    val default_expense_category_id: Long?,
    val default_income_category_id: Long?,
    @ColumnInfo(defaultValue = "''") val my_rules: String,
    @ColumnInfo(defaultValue = "''") val user_nickname: String,
    @ColumnInfo(defaultValue = "''") val ai_nickname: String,
    @ColumnInfo(defaultValue = "0") val revision: Long,
    @ColumnInfo(defaultValue = "0") val updated_at_ms: Long,
    @ColumnInfo(defaultValue = "1") val default_keep_images: Boolean = true,
)

@Dao
interface AiDao {
    @Query("SELECT * FROM ai_settings WHERE id=1")
    fun observeSettings(): Flow<AiSettingsEntity?>

    @Query("SELECT * FROM ai_settings WHERE id=1")
    suspend fun settings(): AiSettingsEntity?

    @Upsert
    suspend fun save(settings: AiSettingsEntity)

    @Query("DELETE FROM ai_settings")
    suspend fun clear()
}
