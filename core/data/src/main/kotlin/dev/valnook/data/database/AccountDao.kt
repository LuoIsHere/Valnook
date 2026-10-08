package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM savings_accounts ORDER BY display_order,id")
    fun accounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM savings_accounts WHERE id=:id")
    suspend fun account(id: Long): AccountEntity?
    @Query("SELECT data FROM account_icon_images WHERE id=:id")
    suspend fun iconImage(id: String): ByteArray?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIconImage(image: AccountIconImageEntity)
    @Query("UPDATE savings_accounts SET icon_type=:type,icon_value=:value WHERE id=:id")
    suspend fun setIcon(id: Long, type: String, value: String)
    @Query("UPDATE savings_accounts SET show_deposit_summary=:deposits, show_investment_summary=:investments WHERE id=:id")
    suspend fun setVisibility(id: Long, deposits: Boolean, investments: Boolean)

    @Insert suspend fun insert_account(value: AccountEntity): Long
    @Query("SELECT COALESCE(MAX(display_order),-1)+1 FROM savings_accounts")
    suspend fun nextDisplayOrder(): Long
    @Query("UPDATE savings_accounts SET display_order=:order WHERE id=:id")
    suspend fun setDisplayOrder(id: Long, order: Long): Int
    @Query("UPDATE savings_accounts SET name=:name,note=:note,updated_at_ms=:now,revision=revision+1 WHERE id=:id")
    suspend fun edit_account(id: Long, name: String, note: String, now: Long): Int
    @Query("SELECT code,fraction_digits FROM currencies WHERE code=:code")
    suspend fun currency(code: String): CurrencyEntity?
}
