package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "wallet_card_images")
data class WalletImageEntity(@PrimaryKey val id: String, val data: ByteArray,
    val width: Int, val height: Int, val tint: Long)

@Entity(tableName = "wallet_cards", foreignKeys = [
    ForeignKey(entity = WalletImageEntity::class, parentColumns = ["id"], childColumns = ["image_key"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["bound_cash_account_id"], onDelete = ForeignKey.SET_NULL)
], indices = [Index("image_key"), Index("bound_cash_account_id"), Index(value = ["display_order", "id"])])
data class WalletCardEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val image_key: String?, val bound_cash_account_id: Long?, val display_order: Long,
    val created_at_ms: Long, val updated_at_ms: Long, val revision: Long, val binding_lost: Boolean = false)

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallet_cards ORDER BY display_order,id") fun observeCards(): Flow<List<WalletCardEntity>>
    @Query("SELECT * FROM wallet_cards ORDER BY display_order,id") suspend fun cards(): List<WalletCardEntity>
    @Query("SELECT * FROM wallet_cards WHERE id=:id") suspend fun card(id: Long): WalletCardEntity?
    @Query("SELECT * FROM wallet_card_images WHERE id=:id") suspend fun image(id: String): WalletImageEntity?
    @Insert suspend fun insert(card: WalletCardEntity): Long
    @Update suspend fun update(card: WalletCardEntity): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertImage(image: WalletImageEntity)
    @Query("DELETE FROM wallet_cards WHERE id=:id AND revision=:revision") suspend fun delete(id: Long, revision: Long): Int
    @Query("UPDATE wallet_cards SET display_order=:position WHERE id=:id") suspend fun position(id: Long, position: Long)
    @Query("DELETE FROM wallet_card_images WHERE id NOT IN (SELECT image_key FROM wallet_cards WHERE image_key IS NOT NULL)") suspend fun collectImages()
    @Query("UPDATE wallet_cards SET bound_cash_account_id=NULL,binding_lost=1,revision=revision+1,updated_at_ms=:now WHERE bound_cash_account_id=:id")
    suspend fun unbind(id: Long, now: Long)
}
