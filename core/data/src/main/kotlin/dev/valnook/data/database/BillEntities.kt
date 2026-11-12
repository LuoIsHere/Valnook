package dev.valnook.data.database

import androidx.room.*

/** Bill categories, two levels deep. A preset keeps its [builtin_key]; its name follows the language while [custom_name] is null. */
@Entity(tableName = "bill_categories",
    foreignKeys = [
        ForeignKey(entity = BillCategoryEntity::class, parentColumns = ["id"], childColumns = ["parent_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["last_cash_account_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["kind", "parent_id", "sort_order"]), Index("parent_id"),
        Index(value = ["builtin_key"], unique = true), Index("last_cash_account_id")])
data class BillCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val parent_id: Long?,
    val builtin_key: String?,
    val custom_name: String?,
    val icon_key: String,
    val color_key: String,
    val sort_order: Long,
    val hidden: Boolean,
    val last_cash_account_id: Long?,
    val revision: Long,
    val created_at_ms: Long,
    val updated_at_ms: Long,
)

/**
 * One bill: an expense, income, transfer, refund or reimbursement. Its money moves through cash_entries rows with
 * source_kind BILL (its own side) and BILL_TRANSFER_IN (a transfer's receiving side), source_id = [id].
 * Amounts are minor units; [rate] is decimal text. See `internal/plan/v0.0.19_part1.md` §2.1.
 */
@Entity(tableName = "bill_entries",
    foreignKeys = [
        ForeignKey(entity = OperationEntity::class, parentColumns = ["operation_id"], childColumns = ["operation_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currency_code"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["account_currency_code"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["to_currency_code"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["cash_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["to_cash_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = BillCategoryEntity::class, parentColumns = ["id"], childColumns = ["category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = BillCategoryEntity::class, parentColumns = ["id"], childColumns = ["sub_category_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = BillEntryEntity::class, parentColumns = ["id"], childColumns = ["parent_id"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index(value = ["operation_id"], unique = true),
        Index(value = ["is_deleted", "occurred_at_ms", "id"], orders = [Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]),
        Index("currency_code"), Index("account_currency_code"), Index("to_currency_code"),
        Index("cash_account_id"), Index("to_cash_account_id"), Index("category_id"), Index("sub_category_id"),
        Index("parent_id")])
data class BillEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val operation_id: String,
    val type: String,
    val occurred_at_ms: Long,
    val amount_minor: Long,
    val currency_code: String,
    val discount_minor: Long,
    val cash_account_id: Long?,
    val account_currency_code: String,
    val account_amount_minor: Long,
    val rate: String,
    val rate_source: String,
    val transfer_kind: String?,
    val to_cash_account_id: Long?,
    val to_currency_code: String?,
    val to_amount_minor: Long?,
    val fee_minor: Long,
    val counterparty: String,
    val category_id: Long?,
    val sub_category_id: Long?,
    val count_in_stats: Boolean,
    val reimbursable: Boolean,
    val reimburse_state: String,
    val parent_id: Long?,
    val batch_id: String?,
    val note: String,
    val revision: Long,
    val is_deleted: Boolean,
    val created_at_ms: Long,
    val updated_at_ms: Long,
    /**
     * Set when [cash_account_id] was emptied because its sub-account was deleted (schema 26). An expense or income
     * whose account is empty without it was recorded on no account at all.
     */
    @ColumnInfo(defaultValue = "0") val account_deleted: Boolean = false,
)
