package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface OverviewRepository {
    fun observeSnapshot(): Flow<AssetSnapshot>
    suspend fun snapshot(): AssetSnapshot
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
}

interface SettingsWriter {
    suspend fun applyChange(change: SettingsChange): AppSettings
}

interface InstrumentRepository {
    fun observeInstruments(): Flow<List<Instrument>>
    fun observeInstrument(id: Long): Flow<Instrument?>
}

interface DataMaintenance {
    suspend fun clearBusinessData()
}

sealed interface SettingsChange { val expectedRevision: Long }
data class SaveFinancialSettings(override val expectedRevision: Long, val baseCurrency: Currency,
    val rates: List<FxRate>) : SettingsChange
data class SaveLanguage(override val expectedRevision: Long, val language: AppLanguage) : SettingsChange
data class SaveGainLossColors(override val expectedRevision: Long,
    val colors: GainLossColorScheme) : SettingsChange

data class CashBalanceChange(val currencyCode: String, val balanceMinor: Long, val expectedRevision: Long?,
    val cashAccountId: Long? = null, val name: String = currencyCode, val note: String = "")
data class SaveAccount(override val operation_id: String, val accountId: Long?, val expectedRevision: Long?,
    val name: String, val note: String, val cashChanges: List<CashBalanceChange>) : FinancialCommand

data class SaveInstrument(override val operation_id: String, val instrumentId: Long?, val expectedRevision: Long?,
    val name: String, val symbol: String, val typeId: Long, val currencyCode: String,
    val currentPriceE5: Long, val currencyPriceConfirmed: Boolean = false) : FinancialCommand

data class SaveOpeningPosition(override val operation_id: String, val accountId: Long, val instrumentId: Long,
    val quantityE8: Long, val costPriceE8: Long?, val occurredAtMs: Long) : FinancialCommand

data class RecordAccountTrade(override val operation_id: String, val accountId: Long, val instrumentId: Long,
    val direction: Direction, val quantityE8: Long, val executionPriceE8: Long,
    val occurredAtMs: Long, val cashLinked: Boolean, val cashAccountId: Long? = null,
    val feeMinor: Long = 0) : FinancialCommand

data class SaveAssetType(override val operation_id: String, val typeId: Long?, val name: String) : FinancialCommand
