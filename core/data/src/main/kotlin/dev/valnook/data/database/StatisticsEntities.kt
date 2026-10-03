package dev.valnook.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val STATISTICS_RULE_VERSION = 2

@Entity(
    tableName = "instrument_price_history",
    foreignKeys = [ForeignKey(
        entity = InstrumentEntity::class,
        parentColumns = ["id"],
        childColumns = ["instrument_id"],
        onDelete = ForeignKey.RESTRICT
    ), ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"],
        childColumns = ["currency_code"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["instrument_id", "is_deleted", "effective_at_ms", "id"]),
        Index("currency_code")]
)
data class InstrumentPriceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instrument_id: Long,
    val price_e5: Long,
    val currency_code: String,
    val effective_at_ms: Long,
    val created_at_ms: Long,
    val updated_at_ms: Long = created_at_ms,
    val revision: Long = 1,
    val is_deleted: Boolean = false
)

@Entity(tableName = "statistics_state")
data class StatisticsStateEntity(
    @androidx.room.PrimaryKey val id: Int = 1,
    val source_revision: Long,
    val rule_version: Int = STATISTICS_RULE_VERSION,
    val baseline_at_ms: Long,
    val earliest_invalidated_epoch_day: Long?
)

@Entity(tableName = "statistics_baseline_items", primaryKeys = ["item_kind", "reference_id"])
data class StatisticsBaselineItemEntity(
    val item_kind: String,
    val reference_id: Long,
    val account_id: Long?,
    val instrument_id: Long?,
    val currency_code: String,
    val amount_long: Long,
    val secondary_long: Long?
)

@Entity(
    tableName = "statistics_cache",
    primaryKeys = ["metric", "epoch_day"],
    indices = [Index(value = ["source_revision", "epoch_day"])]
)
data class StatisticsCacheEntity(
    val metric: String,
    val epoch_day: Long,
    val value_decimal: String?,
    val reliable: Boolean,
    val source_revision: Long,
    val rule_version: Int,
    val computed_at_ms: Long
)

data class StatisticsPositionRow(
    val id: Long,
    val savings_account_id: Long,
    val instrument_id: Long,
    val currency_code: String
)

@Entity(tableName = "demo_labels", primaryKeys = ["entity_kind", "entity_id", "field_name"])
data class DemoLabelEntity(
    val entity_kind: String,
    val entity_id: Long,
    val field_name: String,
    val zh_hans: String,
    val english: String
)
