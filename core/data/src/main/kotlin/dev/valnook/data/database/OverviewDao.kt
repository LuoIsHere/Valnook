package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
abstract class OverviewDao {
    @Query("SELECT * FROM savings_accounts ORDER BY display_order,id")
    abstract suspend fun allAccounts(): List<AccountEntity>
    @Query("SELECT * FROM account_groups ORDER BY display_order,id")
    abstract suspend fun allAccountGroups(): List<AccountGroupEntity>
    @Query("SELECT * FROM cash_accounts ORDER BY savings_account_id,display_order,id")
    abstract suspend fun allCash(): List<CashEntity>
    @Query("SELECT * FROM credit_account_profiles ORDER BY account_id")
    abstract suspend fun allCreditProfiles(): List<CreditAccountProfileEntity>
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
    @Query("UPDATE app_settings SET base_currency=NULL,revision=revision+1 WHERE id=1")
    abstract suspend fun clearFinancialSettings(): Int
    @Insert abstract suspend fun insertRates(values: List<FxRateEntity>)
    @Query("""UPDATE savings_accounts SET name=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='ACCOUNT' AND d.entity_id=savings_accounts.id AND d.field_name='NAME')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='ACCOUNT' AND d.entity_id=savings_accounts.id
        AND d.field_name='NAME' AND savings_accounts.name IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeAccountNames(english: Boolean)
    @Query("""UPDATE savings_accounts SET note=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='ACCOUNT' AND d.entity_id=savings_accounts.id AND d.field_name='NOTE')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='ACCOUNT' AND d.entity_id=savings_accounts.id
        AND d.field_name='NOTE' AND savings_accounts.note IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeAccountNotes(english: Boolean)
    @Query("""UPDATE cash_accounts SET name=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='CASH' AND d.entity_id=cash_accounts.id AND d.field_name='NAME')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='CASH' AND d.entity_id=cash_accounts.id
        AND d.field_name='NAME' AND cash_accounts.name IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeCashNames(english: Boolean)
    @Query("""UPDATE asset_types SET name=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='TYPE' AND d.entity_id=asset_types.id AND d.field_name='NAME'),
        normalized_name=lower((SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='TYPE' AND d.entity_id=asset_types.id AND d.field_name='NAME'))
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='TYPE' AND d.entity_id=asset_types.id
        AND d.field_name='NAME' AND asset_types.name IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeTypes(english: Boolean)
    @Query("""UPDATE instruments SET name=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='INSTRUMENT' AND d.entity_id=instruments.id AND d.field_name='NAME')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='INSTRUMENT' AND d.entity_id=instruments.id
        AND d.field_name='NAME' AND instruments.name IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeInstruments(english: Boolean)
    @Query("""UPDATE cash_accounts SET note=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='CASH' AND d.entity_id=cash_accounts.id AND d.field_name='NOTE')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='CASH' AND d.entity_id=cash_accounts.id
        AND d.field_name='NOTE' AND cash_accounts.note IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeCashNotes(english: Boolean)
    @Query("""UPDATE cash_entries SET note=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='ENTRY' AND d.entity_id=cash_entries.id AND d.field_name='NOTE')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='ENTRY' AND d.entity_id=cash_entries.id
        AND d.field_name='NOTE' AND cash_entries.note IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeEntryNotes(english: Boolean)
    @Query("""UPDATE account_groups SET name=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='GROUP' AND d.entity_id=account_groups.id AND d.field_name='NAME')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='GROUP' AND d.entity_id=account_groups.id
        AND d.field_name='NAME' AND account_groups.name IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeGroups(english: Boolean)
    @Query("""UPDATE wallet_cards SET name=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='CARD' AND d.entity_id=wallet_cards.id AND d.field_name='NAME')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='CARD' AND d.entity_id=wallet_cards.id
        AND d.field_name='NAME' AND wallet_cards.name IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeWalletCards(english: Boolean)
    /** Faces are keyed by their digest, so their labels are matched by rowid. */
    @Query("""UPDATE wallet_card_images SET label=(SELECT CASE WHEN :english THEN english ELSE zh_hans END
        FROM demo_labels d WHERE d.entity_kind='FACE' AND d.entity_id=wallet_card_images.rowid AND d.field_name='LABEL')
        WHERE EXISTS(SELECT 1 FROM demo_labels d WHERE d.entity_kind='FACE' AND d.entity_id=wallet_card_images.rowid
        AND d.field_name='LABEL' AND wallet_card_images.label IN (d.zh_hans,d.english))""")
    abstract suspend fun localizeWalletFaces(english: Boolean)
    @Transaction
    open suspend fun snapshot(): OverviewRows = OverviewRows(allAccounts(), allCash(), allCreditProfiles(), openDeposits(),
        allPositions(), allInstruments(), settings(), rates(), allAccountGroups())
    @Transaction
    open suspend fun settingsSnapshot(): Pair<SettingsEntity?, List<FxRateEntity>> = settings() to rates()
}

data class OverviewRows(val accounts: List<AccountEntity>, val cash: List<CashEntity>,
    val creditProfiles: List<CreditAccountProfileEntity>,
    val deposits: List<DepositEntity>, val positions: List<InvestmentWithType>,
    val instruments: List<InstrumentWithType>, val settings: SettingsEntity?, val rates: List<FxRateEntity>,
    val groups: List<AccountGroupEntity>)
