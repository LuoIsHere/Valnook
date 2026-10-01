package dev.valnook.data.database

import androidx.room.*

@Entity(tableName = "instruments", foreignKeys = [
    ForeignKey(entity = TypeEntity::class, parentColumns = ["id"], childColumns = ["asset_type_id"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["currency_code"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("asset_type_id"), Index("currency_code"), Index(value = ["name", "id"])])
data class InstrumentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0,
    val asset_type_id: Long, val name: String, val symbol: String, val currency_code: String,
    val current_price_e5: Long, val currency_locked: Boolean, val revision: Long,
    val price_updated_at_ms: Long, val created_at_ms: Long, val updated_at_ms: Long)

data class InstrumentWithType(@Embedded val instrument: InstrumentEntity, val type_name: String)
