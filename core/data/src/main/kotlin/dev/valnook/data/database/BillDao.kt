package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A sub-account with what the bill account picker shows; credit fields are null for savings sub-accounts. */
data class BillAccountRow(
    val id: Long,
    val savings_account_id: Long,
    val account_name: String,
    val name: String,
    val currency_code: String,
    val balance_minor: Long,
    val is_favorite: Boolean,
    val favorite_order: Long,
    val show_on_accounts_page: Boolean,
    val account_order: Long,
    val display_order: Long,
    val icon_type: String,
    val icon_value: String,
    val is_credit: Boolean,
    val credit_limit_minor: Long?,
    val limit_source_account_id: Long?,
)

@Dao
interface BillDao {
    @Query("SELECT * FROM bill_categories ORDER BY kind,parent_id IS NOT NULL,sort_order,id")
    fun observeCategories(): Flow<List<BillCategoryEntity>>

    @Query("SELECT * FROM bill_categories WHERE id=:id")
    suspend fun category(id: Long): BillCategoryEntity?

    @Query("SELECT * FROM bill_categories WHERE kind=:kind AND parent_id IS :parentId ORDER BY sort_order,id")
    suspend fun siblings(kind: String, parentId: Long?): List<BillCategoryEntity>

    @Query("SELECT COALESCE(MAX(sort_order),-1)+1 FROM bill_categories WHERE kind=:kind AND parent_id IS :parentId")
    suspend fun nextSortOrder(kind: String, parentId: Long?): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCategory(value: BillCategoryEntity): Long

    @Update
    suspend fun updateCategory(value: BillCategoryEntity): Int

    @Query("UPDATE bill_categories SET sort_order=:order,revision=revision+1,updated_at_ms=:now WHERE id=:id")
    suspend fun setCategoryOrder(id: Long, order: Long, now: Long): Int

    /** The account a category was last used with; null when that bill was on no account. */
    @Query("UPDATE bill_categories SET last_cash_account_id=:cashAccountId WHERE id=:id")
    suspend fun rememberAccount(id: Long, cashAccountId: Long?): Int

    @Query("DELETE FROM bill_categories WHERE id=:id")
    suspend fun deleteCategory(id: Long): Int

    /** Bills on [id] or its subcategories, deleted ones included (they still refer to it). */
    @Query("""SELECT COUNT(*) FROM bill_entries WHERE category_id=:id OR sub_category_id=:id
        OR category_id IN (SELECT id FROM bill_categories WHERE parent_id=:id)""")
    suspend fun categoryReferences(id: Long): Int

    @Query("""SELECT COUNT(*) FROM bill_entries WHERE is_deleted=0 AND (category_id=:id OR sub_category_id=:id)""")
    suspend fun categoryUsage(id: Long): Int

    @Query("SELECT * FROM bill_entries WHERE id=:id")
    suspend fun bill(id: Long): BillEntryEntity?

    @Query("SELECT * FROM bill_entries WHERE id=:id AND is_deleted=0")
    fun observeBill(id: Long): Flow<BillEntryEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBill(value: BillEntryEntity): Long

    @Update
    suspend fun updateBill(value: BillEntryEntity): Int

    @Query("""SELECT * FROM bill_entries WHERE is_deleted=0 AND occurred_at_ms>=:startMs AND occurred_at_ms<:endMs
        ORDER BY occurred_at_ms DESC,id DESC""")
    fun observeRange(startMs: Long, endMs: Long): Flow<List<BillEntryEntity>>

    @Query("""SELECT * FROM bill_entries WHERE is_deleted=0 AND note LIKE '%' || :escaped || '%' ESCAPE '\'
        ORDER BY occurred_at_ms DESC,id DESC LIMIT :limit""")
    fun observeSearch(escaped: String, limit: Int): Flow<List<BillEntryEntity>>

    @Query("""SELECT cash_account_id FROM bill_entries WHERE is_deleted=0 AND cash_account_id IS NOT NULL
        AND type IN ('EXPENSE','INCOME') ORDER BY created_at_ms DESC,id DESC LIMIT 1""")
    suspend fun lastUsedCashAccountId(): Long?

    @Query("SELECT MIN(occurred_at_ms) FROM bill_entries WHERE is_deleted=0")
    fun observeFirstBillAt(): Flow<Long?>

    /** Notes of bills on exactly this category: a subcategory's own, or a top-level one's without a subcategory. */
    @Query("""SELECT note FROM bill_entries WHERE is_deleted=0 AND note<>'' AND category_id=:categoryId
        AND ((:subCategoryId IS NULL AND sub_category_id IS NULL) OR sub_category_id=:subCategoryId)
        GROUP BY note ORDER BY COUNT(*) DESC,MAX(occurred_at_ms) DESC LIMIT :limit""")
    fun observeRecentNotes(categoryId: Long, subCategoryId: Long?, limit: Int): Flow<List<String>>

    /** The cash entries a bill moves, deleted ones included. */
    @Query("SELECT * FROM cash_entries WHERE source_kind IN ('BILL','BILL_TRANSFER_IN') AND source_id=:billId ORDER BY id")
    suspend fun legs(billId: Long): List<CashEntryEntity>

    @Query("DELETE FROM cash_entries WHERE id=:id")
    suspend fun deleteLeg(id: Long): Int

    @Query("""UPDATE cash_entries SET delta_minor=:delta,occurred_at_ms=:occurred,is_deleted=:deleted,
        revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:revision""")
    suspend fun updateLeg(id: Long, revision: Long, delta: Long, occurred: Long, deleted: Boolean, now: Long): Int

    @Query("""SELECT c.id,c.savings_account_id,a.name AS account_name,c.name,c.currency_code,c.balance_minor,
        c.is_favorite,c.favorite_order,c.show_on_accounts_page,a.display_order AS account_order,c.display_order,
        a.icon_type,a.icon_value,(p.account_id IS NOT NULL) AS is_credit,p.credit_limit_minor,p.limit_source_account_id
        FROM cash_accounts c JOIN savings_accounts a ON a.id=c.savings_account_id
        JOIN account_groups g ON g.id=a.group_id
        LEFT JOIN credit_account_profiles p ON p.account_id=c.id
        ORDER BY g.display_order,g.id,a.display_order,a.id,c.display_order,c.id""")
    fun observeAccountRows(): Flow<List<BillAccountRow>>

    @Query("UPDATE cash_accounts SET is_favorite=0,favorite_order=0 WHERE is_favorite<>0")
    suspend fun clearFavorites(): Int

    @Query("UPDATE cash_accounts SET is_favorite=1,favorite_order=:order WHERE id=:id")
    suspend fun setFavorite(id: Long, order: Long): Int

    /** Bills whose own or receiving side is one of [cashIds], deleted ones included. */
    @Query("SELECT * FROM bill_entries WHERE cash_account_id IN (:cashIds) OR to_cash_account_id IN (:cashIds)")
    suspend fun billsOnAccounts(cashIds: List<Long>): List<BillEntryEntity>

    @Query("UPDATE bill_categories SET last_cash_account_id=NULL WHERE last_cash_account_id IN (:cashIds)")
    suspend fun forgetAccounts(cashIds: List<Long>): Int
}
