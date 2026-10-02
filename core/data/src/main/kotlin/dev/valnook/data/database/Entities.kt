package dev.valnook.data.database

import androidx.room.*

@Entity(tableName = "savings_accounts")
data class AccountEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val note: String, val created_at_ms: Long, val updated_at_ms: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1)

@Entity(tableName = "currencies")
data class CurrencyEntity(@PrimaryKey val code: String, val fraction_digits: Int)

@Entity(tableName = "operations")
data class OperationEntity(@PrimaryKey val operation_id: String, val kind: String,
    val request_fingerprint: String, val result_kind: String?, val result_id: Long?, val created_at_ms: Long)

@Entity(tableName = "cash_accounts",
    foreignKeys = [
        ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["savings_account_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currency_code"], onDelete = ForeignKey.RESTRICT)
    ], indices = [Index(value = ["savings_account_id", "currency_code", "id"]), Index("currency_code")])
data class CashEntity(val savings_account_id: Long, val currency_code: String, val balance_minor: Long,
    val revision: Long, val updated_at_ms: Long, @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = currency_code, val note: String = "", val currency_locked: Boolean = true,
    val created_at_ms: Long = updated_at_ms)

@Entity(tableName = "term_deposits", foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["savings_account_id"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currency_code"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["operation_id"], childColumns = ["open_operation_id"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = OperationEntity::class, parentColumns = ["operation_id"], childColumns = ["close_operation_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["open_cash_account_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["close_cash_account_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["savings_account_id", "status", "end_epoch_day", "id"]),
        Index(value = ["savings_account_id", "status", "start_epoch_day", "id"], orders = [Index.Order.ASC,Index.Order.ASC,Index.Order.DESC,Index.Order.DESC]),
        Index("currency_code"), Index(value = ["open_operation_id"], unique = true),
        Index(value = ["close_operation_id"], unique = true), Index("open_cash_account_id"), Index("close_cash_account_id")])
data class DepositEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savings_account_id: Long, val currency_code: String, val principal_minor: Long,
    val annual_rate_percent_e8: Long, val start_epoch_day: Long, val end_epoch_day: Long,
    val interest_rule: String = "ACT_365F_SIMPLE", val calculation_version: Int = 1,
    val rounding_mode: String = "HALF_UP", val expected_interest_minor: Long,
    val status: String = "OPEN", val open_cash_linked: Boolean, val close_cash_linked: Boolean? = null,
    val open_cash_account_id: Long? = null, val close_cash_account_id: Long? = null,
    val open_operation_id: String, val close_operation_id: String? = null, val closed_at_ms: Long? = null,
    val created_at_ms: Long, val updated_at_ms: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1)

@Entity(tableName = "asset_types", indices = [Index(value = ["normalized_name"], unique = true)])
data class TypeEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val normalized_name: String, val created_at_ms: Long, val updated_at_ms: Long)

@Entity(tableName = "investments", foreignKeys = [ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["savings_account_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = InstrumentEntity::class, parentColumns = ["id"], childColumns = ["instrument_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["savings_account_id", "instrument_id"], unique = true),
        Index(value = ["instrument_id", "savings_account_id"]),
        Index(value = ["savings_account_id", "position_state", "last_activity_at_ms", "id"], orders = [Index.Order.ASC,Index.Order.ASC,Index.Order.DESC,Index.Order.DESC]),
    ])
data class InvestmentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val savings_account_id: Long, val instrument_id: Long,
    val opening_quantity_e8: Long, val holding_quantity_e8: Long, val revision: Long,
    val created_at_ms: Long, val updated_at_ms: Long,
    val opening_cost_price_e8: Long? = null,
    val opening_at_ms: Long,
    val remaining_cost: String?, val realized_profit: String?,
    val chronology_valid: Boolean, val algorithm_version: Int,
    @ColumnInfo(defaultValue = "'PENDING'") val position_state: String = "PENDING",
    @ColumnInfo(defaultValue = "0") val last_activity_at_ms: Long = 0)
data class InvestmentWithType(@Embedded val asset: InvestmentEntity, val type_name: String,
    val asset_type_id: Long, val name: String, val symbol: String, val currency_code: String,
    val current_price_e5: Long, val price_updated_at_ms: Long)

@Entity(tableName = "investment_trades", foreignKeys = [
    ForeignKey(entity = InvestmentEntity::class, parentColumns = ["id"], childColumns = ["investment_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["operation_id"], childColumns = ["operation_id"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currency_code"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["cash_account_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["investment_id", "is_deleted", "occurred_at_ms", "id"], orders = [Index.Order.ASC, Index.Order.ASC, Index.Order.DESC, Index.Order.DESC]),
        Index(value = ["operation_id"], unique = true), Index("currency_code"), Index("cash_account_id")])
data class TradeEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val investment_id: Long,
    val operation_id: String, val direction: String, val quantity_e8: Long, val execution_price_e8: Long,
    val amount_minor: Long, val currency_code: String, val cash_linked: Boolean, val cash_account_id: Long? = null,
    val occurred_at_ms: Long, val created_at_ms: Long,
    @ColumnInfo(defaultValue = "1") val revision: Long = 1,
    @ColumnInfo(defaultValue = "0") val is_deleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updated_at_ms: Long = 0,
    @ColumnInfo(defaultValue = "0") val fee_minor: Long = 0)

@Entity(tableName = "cash_entries", foreignKeys = [
    ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["cash_account_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = OperationEntity::class, parentColumns = ["operation_id"],
        childColumns = ["original_operation_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["original_operation_id", "cash_account_id"], unique = true),
        Index(value = ["source_kind", "source_id"], unique = true),
        Index(value = ["cash_account_id", "is_deleted", "occurred_at_ms", "id"],
            orders = [Index.Order.ASC,Index.Order.ASC,Index.Order.DESC,Index.Order.DESC]),
        Index(value = ["savings_account_id", "currency_code"])])
data class CashEntryEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val original_operation_id: String, val savings_account_id: Long, val currency_code: String,
    val cash_account_id: Long, val source_kind: String, val source_id: Long?, val delta_minor: Long,
    val occurred_at_ms: Long, val note: String, val revision: Long,
    val is_deleted: Boolean, val created_at_ms: Long, val updated_at_ms: Long)

data class CashEntryWithSource(@Embedded val entry: CashEntryEntity, val investment_id: Long?,
    val cash_account_name: String)

@Entity(tableName = "cash_movements", foreignKeys = [ForeignKey(entity = OperationEntity::class, parentColumns = ["operation_id"], childColumns = ["operation_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CashEntity::class, parentColumns = ["id"], childColumns = ["cash_account_id"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["operation_id", "cash_account_id"], unique = true), Index(value = ["cash_account_id", "id"]),
        Index(value = ["savings_account_id", "currency_code"])])
data class MovementEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val operation_id: String,
    val savings_account_id: Long, val currency_code: String, val cash_account_id: Long,
    val reason: String, val delta_minor: Long,
    val balance_before_minor: Long, val balance_after_minor: Long, val created_at_ms: Long)
