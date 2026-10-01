package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OverviewDao {
    @Query("SELECT * FROM savings_accounts ORDER BY id")
    abstract suspend fun allAccounts(): List<AccountEntity>
    @Query("SELECT * FROM cash_balances ORDER BY savings_account_id,currency_code")
    abstract suspend fun allCash(): List<CashEntity>
    @Query("SELECT * FROM term_deposits WHERE status='OPEN' ORDER BY savings_account_id,id")
    abstract suspend fun openDeposits(): List<DepositEntity>
    @Query(POSITION_PROJECTION + " ORDER BY p.savings_account_id,p.last_activity_at_ms DESC,p.id DESC")
    abstract suspend fun allPositions(): List<InvestmentWithType>
    @Query(INSTRUMENT_PROJECTION + " ORDER BY s.name,s.id")
    abstract suspend fun allInstruments(): List<InstrumentWithType>
    @Query("SELECT * FROM app_settings WHERE id=1")
    abstract suspend fun settings(): SettingsEntity?
    @Query("SELECT * FROM fx_rates ORDER BY source_currency,target_currency")
    abstract suspend fun rates(): List<FxRateEntity>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertSettings(value: SettingsEntity)
    @Update abstract suspend fun updateSettings(value: SettingsEntity): Int
    @Query("DELETE FROM fx_rates")
    abstract suspend fun clearRates()
    @Insert abstract suspend fun insertRates(values: List<FxRateEntity>)
    @Transaction
    open suspend fun snapshot(): OverviewRows = OverviewRows(allAccounts(), allCash(), openDeposits(),
        allPositions(), allInstruments(), settings(), rates())
    @Transaction
    open suspend fun settingsSnapshot(): Pair<SettingsEntity?, List<FxRateEntity>> = settings() to rates()
}

data class OverviewRows(val accounts: List<AccountEntity>, val cash: List<CashEntity>,
    val deposits: List<DepositEntity>, val positions: List<InvestmentWithType>,
    val instruments: List<InstrumentWithType>, val settings: SettingsEntity?, val rates: List<FxRateEntity>)
