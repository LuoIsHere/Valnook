package dev.valnook.data.database

import androidx.room.*

@Entity(tableName = "app_settings", foreignKeys = [ForeignKey(entity = CurrencyEntity::class,
    parentColumns = ["code"], childColumns = ["base_currency"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("base_currency")])
data class SettingsEntity(@PrimaryKey val id: Int = 1, val base_currency: String?, val revision: Long,
    val language: String = "SYSTEM", val gain_loss_scheme: String = "GREEN_GAIN",
    val navigation_order: String = "ACCOUNTS,INVESTMENTS,STATISTICS,SETTINGS",
    val navigation_visible: String = "ACCOUNTS,INVESTMENTS,STATISTICS,SETTINGS")

@Entity(tableName = "fx_rates", primaryKeys = ["source_currency", "target_currency"], foreignKeys = [
    ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["source_currency"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CurrencyEntity::class, parentColumns = ["code"], childColumns = ["target_currency"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("target_currency")])
data class FxRateEntity(val source_currency: String, val target_currency: String, val rate: String, val updated_at_ms: Long)
