package dev.valnook.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CreditDao {
    @Query("SELECT * FROM credit_account_profiles ORDER BY account_id")
    suspend fun all(): List<CreditAccountProfileEntity>

    @Query("SELECT * FROM credit_account_profiles WHERE account_id=:accountId")
    suspend fun profile(accountId: Long): CreditAccountProfileEntity?

    @Query("SELECT * FROM credit_account_profiles WHERE limit_source_account_id=:rootId ORDER BY account_id")
    suspend fun dependents(rootId: Long): List<CreditAccountProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: CreditAccountProfileEntity)

    @Query("DELETE FROM credit_account_profiles WHERE account_id=:accountId")
    suspend fun delete(accountId: Long): Int
}
