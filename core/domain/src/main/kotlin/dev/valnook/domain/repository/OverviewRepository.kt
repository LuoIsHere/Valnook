package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface OverviewRepository {
    fun observeSnapshot(): Flow<AssetSnapshot>
    suspend fun snapshot(): AssetSnapshot
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
    suspend fun saveSettings(settings: AppSettings, expectedRevision: Long)
}

interface InstrumentRepository {
    fun observeInstruments(): Flow<List<Instrument>>
    fun observeInstrument(id: Long): Flow<Instrument?>
    suspend fun saveInstrument(command: SaveInstrument): OperationResult
}

data class CashBalanceChange(val currencyCode: String, val balanceMinor: Long, val expectedRevision: Long?)
data class SaveAccount(override val operation_id: String, val accountId: Long?, val expectedRevision: Long?,
    val name: String, val note: String, val cashChanges: List<CashBalanceChange>) : FinancialCommand

data class SaveInstrument(override val operation_id: String, val instrumentId: Long?, val expectedRevision: Long?,
    val name: String, val symbol: String, val typeId: Long, val currencyCode: String,
    val currentPriceE5: Long, val currencyPriceConfirmed: Boolean = false) : FinancialCommand

data class SaveOpeningPosition(override val operation_id: String, val accountId: Long, val instrumentId: Long,
    val quantityE8: Long, val costPriceE8: Long?, val occurredAtMs: Long) : FinancialCommand

data class RecordAccountTrade(override val operation_id: String, val accountId: Long, val instrumentId: Long,
    val direction: Direction, val quantityE8: Long, val executionPriceE8: Long,
    val occurredAtMs: Long, val cashLinked: Boolean) : FinancialCommand

data class SaveAssetType(override val operation_id: String, val typeId: Long?, val name: String) : FinancialCommand
