package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface OverviewRepository {
    fun observeSnapshot(): Flow<AssetSnapshot>
    suspend fun snapshot(): AssetSnapshot
    suspend fun accountIconImage(id: String): ByteArray? = null
    /** The switch and latest stored rates, for showing today's rate next to a currency. */
    fun observeFxContext(): Flow<dev.valnook.domain.fx.FxContext> =
        kotlinx.coroutines.flow.flowOf(dev.valnook.domain.fx.FxContext())
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
    /** One account's net assets for each day of the last three months, ending with today's live value. */
    suspend fun loadAccountTrend(accountId: Long): List<StatisticsPoint> = emptyList()
}

interface InstrumentRepository {
    fun observeInstruments(): Flow<List<Instrument>>
    fun observeInstrument(id: Long): Flow<Instrument?>
    /** The latest [limit] recorded prices, newest first; older ones stay stored for the statistics. */
    fun observePriceHistory(instrumentId: Long, limit: Int = PRICE_TREND_POINTS): Flow<List<InstrumentPricePoint>> =
        kotlinx.coroutines.flow.flowOf(emptyList())
    companion object { const val PRICE_TREND_POINTS = 30 }
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
    val credit: CreditAccountInput? = null, val displayOrder: Long? = null,
    val includeInAvailableCash: Boolean? = null, val showOnAccountsPage: Boolean? = null)
typealias CashBalanceChange = BalanceAccountChange
data class SaveAccount(override val operation_id: String, val accountId: Long?, val expectedRevision: Long?,
    val name: String, val note: String, val cashChanges: List<BalanceAccountChange>,
    val iconChange: AccountIconChange? = null, val showDepositSummary: Boolean? = null,
    val showInvestmentSummary: Boolean? = null) : FinancialCommand

data class DeleteBalanceAccount(override val operation_id: String, val accountId: Long,
    val balanceAccountId: Long, val expectedRevision: Long,
    val confirmation: DeletionConfirmation? = null) : FinancialCommand

data class DeleteAccount(override val operation_id: String, val accountId: Long,
    val expectedRevision: Long, val confirmation: DeletionConfirmation) : FinancialCommand

data class SaveInstrument(override val operation_id: String, val instrumentId: Long?, val expectedRevision: Long?,
    val name: String, val symbol: String, val typeId: Long, val currencyCode: String,
    val currentPriceE5: Long, val currencyPriceConfirmed: Boolean = false) : FinancialCommand

data class EditInstrumentPrice(override val operation_id: String, val priceRecordId: Long,
    val expectedRevision: Long, val priceE5: Long, val effectiveAtMs: Long) : FinancialCommand

data class InstrumentPriceChange(val instrumentId: Long, val expectedRevision: Long, val priceE5: Long)
/**
 * [recordUnchanged]: a price saved by hand counts as an update even when it is the same, so it is dated now and
 * recorded. The web batch leaves it off and skips the prices it did not change.
 */
data class UpdateInstrumentPrices(override val operation_id: String,
    val changes: List<InstrumentPriceChange>, val recordUnchanged: Boolean = false) : FinancialCommand

data class CreateInvestmentPosition(override val operation_id: String, val accountId: Long,
    val instrumentId: Long) : FinancialCommand

data class SaveAssetType(override val operation_id: String, val typeId: Long?, val name: String) : FinancialCommand
/** Moves the class's instruments to the default class, then removes it; the default class itself stays. */
data class DeleteAssetType(override val operation_id: String, val typeId: Long) : FinancialCommand
