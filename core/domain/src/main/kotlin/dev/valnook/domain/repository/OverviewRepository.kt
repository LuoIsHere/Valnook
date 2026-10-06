package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface OverviewRepository {
    fun observeSnapshot(): Flow<AssetSnapshot>
    suspend fun snapshot(): AssetSnapshot
    suspend fun accountIconImage(id: String): ByteArray? = null
}

interface SettingsRepository {
    fun observeSettings(): Flow<AppSettings>
}

interface SettingsWriter {
    suspend fun applyChange(change: SettingsChange): AppSettings
}

interface StatisticsRepository {
    fun observeRevision(): Flow<Long>
    suspend fun loadSeries(request: StatisticsRequest): StatisticsSeries
    suspend fun loadCurrent(): CurrentStatistics
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
data class SaveNavigationConfiguration(override val expectedRevision: Long,
    val configuration: NavigationConfiguration) : SettingsChange

data class BalanceAccountChange(val currencyCode: String, val balanceMinor: Long, val expectedRevision: Long?,
    val cashAccountId: Long? = null, val name: String = currencyCode, val note: String = "",
    val type: BalanceAccountType = BalanceAccountType.SAVINGS,
    val credit: CreditAccountInput? = null, val displayOrder: Long? = null)
typealias CashBalanceChange = BalanceAccountChange
data class SaveAccount(override val operation_id: String, val accountId: Long?, val expectedRevision: Long?,
    val name: String, val note: String, val cashChanges: List<BalanceAccountChange>,
    val iconChange: AccountIconChange? = null) : FinancialCommand

data class DeleteBalanceAccount(override val operation_id: String, val accountId: Long,
    val balanceAccountId: Long, val expectedRevision: Long) : FinancialCommand

data class SaveInstrument(override val operation_id: String, val instrumentId: Long?, val expectedRevision: Long?,
    val name: String, val symbol: String, val typeId: Long, val currencyCode: String,
    val currentPriceE5: Long, val currencyPriceConfirmed: Boolean = false) : FinancialCommand

data class EditInstrumentPrice(override val operation_id: String, val priceRecordId: Long,
    val expectedRevision: Long, val priceE5: Long, val effectiveAtMs: Long) : FinancialCommand

data class CreateInvestmentPosition(override val operation_id: String, val accountId: Long,
    val instrumentId: Long) : FinancialCommand

data class SaveAssetType(override val operation_id: String, val typeId: Long?, val name: String) : FinancialCommand
