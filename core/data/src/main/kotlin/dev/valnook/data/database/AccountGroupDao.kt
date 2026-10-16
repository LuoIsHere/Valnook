package dev.valnook.data.database

import androidx.room.*
import dev.valnook.domain.model.AccountGroup

/** The default group keeps id 1 and an empty name; its label is localized at display time. */
@Entity(tableName = "account_groups")
data class AccountGroupEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val display_order: Long, @ColumnInfo(defaultValue = "0") val collapsed: Boolean = false,
    val created_at_ms: Long, val updated_at_ms: Long) {
    fun toModel() = AccountGroup(id, name, display_order, collapsed)

    companion object {
        const val DEFAULT_ID = AccountGroup.DEFAULT_ID
    }
}

@Dao
interface AccountGroupDao {
    @Query("SELECT * FROM account_groups ORDER BY display_order,id")
    suspend fun groups(): List<AccountGroupEntity>
    @Query("SELECT * FROM account_groups WHERE id=:id")
    suspend fun group(id: Long): AccountGroupEntity?
    @Insert suspend fun insert(value: AccountGroupEntity): Long
    @Query("UPDATE account_groups SET name=:name,updated_at_ms=:now WHERE id=:id")
    suspend fun rename(id: Long, name: String, now: Long): Int
    @Query("UPDATE account_groups SET display_order=:order WHERE id=:id")
    suspend fun setDisplayOrder(id: Long, order: Long): Int
    @Query("UPDATE account_groups SET collapsed=:collapsed WHERE id=:id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean): Int
    @Query("DELETE FROM account_groups WHERE id=:id")
    suspend fun delete(id: Long): Int

    @Query("SELECT id FROM savings_accounts WHERE group_id=:groupId ORDER BY display_order,id")
    suspend fun accountIds(groupId: Long): List<Long>
    @Query("UPDATE savings_accounts SET group_id=:groupId,display_order=:order WHERE id=:accountId")
    suspend fun placeAccount(accountId: Long, groupId: Long, order: Long): Int

    /** Accounts whose group is missing sort with the default group. */
    @Query("""SELECT a.id FROM savings_accounts a LEFT JOIN account_groups g ON g.id=a.group_id
        ORDER BY COALESCE(g.display_order,(SELECT display_order FROM account_groups WHERE id=${AccountGroupEntity.DEFAULT_ID}),0),
        a.display_order,a.id""")
    suspend fun accountIdsInGroupOrder(): List<Long>
}

/**
 * Rewrites savings_accounts.display_order as one global sequence (group order first, then position in the
 * group), so every reader that sorts by display_order alone sees the grouped order. Call inside a transaction.
 */
internal suspend fun ValnookDatabase.normalizeAccountOrder() {
    accountGroups().accountIdsInGroupOrder().forEachIndexed { index, id -> accounts().setDisplayOrder(id, index.toLong()) }
}
