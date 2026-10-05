package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM savings_accounts ORDER BY display_order,id")
    fun accounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM savings_accounts WHERE id=:id")
    suspend fun account(id: Long): AccountEntity?
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
