package dev.valnook.data.database

import androidx.room.*

/**
 * Non-secret local linkage only; deliberately absent from BackupContract.
 * 安全边界：这里只存不含隐私值的本机随机关联标识，不得加入备份；恢复/删除必须事务性撤销关联。
 * Do not replace the UUID with a card ID/name: restore may reuse those and reveal another card's secrets.
 * 禁止以卡片 ID 或名称替代随机标识，备份恢复可能复用 ID，导致另一张卡片读取旧私密内容。
 */
@Entity(tableName = "local_wallet_identity", foreignKeys = [ForeignKey(
    entity = WalletCardEntity::class, parentColumns = ["id"], childColumns = ["card_id"],
    onDelete = ForeignKey.CASCADE)], indices = [Index(value = ["token"], unique = true)])
data class WalletPrivateIdentity(@PrimaryKey val card_id: Long, val token: String)

@Dao
interface WalletPrivateIdentityDao {
    @Query("SELECT token FROM local_wallet_identity WHERE card_id=:id") suspend fun token(id: Long): String?
    @Query("SELECT token FROM local_wallet_identity") suspend fun tokens(): List<String>
    @Insert suspend fun insert(identity: WalletPrivateIdentity)
}

val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_wallet_identity (card_id INTEGER NOT NULL, token TEXT NOT NULL, PRIMARY KEY(card_id), FOREIGN KEY(card_id) REFERENCES wallet_cards(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_local_wallet_identity_token ON local_wallet_identity(token)")
    }
}
