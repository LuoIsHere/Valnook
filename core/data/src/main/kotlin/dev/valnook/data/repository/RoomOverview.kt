package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.audit.AuditRecorder
import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.time.Clock

class RoomOverview(private val db: ValnookDatabase) : OverviewRepository {
    override fun observeSnapshot(): Flow<AssetSnapshot> = db.invalidationTracker.createFlow(
        "savings_accounts", "cash_accounts", "credit_account_profiles", "term_deposits", "investments", "instruments",
        "asset_types", "app_settings", "fx_rates"
    ).map { snapshot() }.flowOn(Dispatchers.IO)

    override suspend fun accountIconImage(id: String): ByteArray? = db.accounts().iconImage(id)

    override suspend fun snapshot(): AssetSnapshot {
        val rows = db.overview().snapshot()
        val profiles = rows.creditProfiles.associateBy { it.account_id }
        return AssetSnapshot(rows.accounts.map { SavingsAccount(it.id, it.name, it.note, it.revision, AccountIcon(AccountIconType.valueOf(it.icon_type), it.icon_value)) },
            rows.cash.map { CashAccount(it.savings_account_id, Currency.of(it.currency_code), it.balance_minor, it.revision,
                it.id, it.name, it.note, it.currency_locked, profiles[it.id]?.toModel()) },
            rows.deposits.map { it.toModel() }, rows.positions.map { it.toModel() },
            rows.instruments.map { it.toModel() }, settingsModel(rows.settings, rows.rates))
    }
}

class RoomSettings(private val db: ValnookDatabase, private val clock: Clock) : SettingsRepository, SettingsWriter {
    private val audit = AuditRecorder(db, clock)
    override fun observeSettings(): Flow<AppSettings> = db.invalidationTracker.createFlow("app_settings", "fx_rates")
        .map { val (settings, rates) = db.overview().settingsSnapshot()
            settingsModel(settings, rates) }
        .flowOn(Dispatchers.IO)

    override suspend fun applyChange(change: SettingsChange): AppSettings {
        // Settings and pairs share one revision and transaction, avoiding mixed-base snapshots.
        return db.withTransaction {
            val dao = db.overview()
            val auditBefore = audit.settingsJson()
            val previous = dao.settings()
            if ((previous?.revision ?: 0) != change.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
            val current = settingsModel(previous, dao.rates())
            val settings = when (change) {
                is SaveFinancialSettings -> current.copy(baseCurrency = change.baseCurrency, rates = change.rates)
                is SaveLanguage -> current.copy(language = change.language)
                is SaveGainLossColors -> current.copy(gainLossColors = change.colors)
                is SaveNavigationConfiguration -> current.copy(navigation = change.configuration)
            }
            settings.baseCurrency?.let { Currency.of(it.code) }
            if (settings.rates.distinctBy { it.sourceCurrency.code to it.targetCurrency.code }.size != settings.rates.size) {
                throw DomainException(ErrorCode.DUPLICATE_CURRENCY)
            }
            settings.rates.forEach {
                Currency.of(it.sourceCurrency.code)
                Currency.of(it.targetCurrency.code)
                if (it.rate.signum() <= 0) throw DomainException(ErrorCode.POSITIVE)
                if (it.rate.stripTrailingZeros().scale() > 12) throw DomainException(ErrorCode.PRECISION)
                if (it.rate.precision() > 40) throw DomainException(ErrorCode.OVERFLOW)
            }
            val value = SettingsEntity(base_currency = settings.baseCurrency?.code,
                revision = dev.valnook.domain.money.DecimalRules.add(change.expectedRevision, 1),
                language = settings.language.name, gain_loss_scheme = settings.gainLossColors.name,
                navigation_order = settings.navigation.order.joinToString(",") { it.name },
                navigation_visible = settings.navigation.visibleInOrder.joinToString(",") { it.name })
            if (previous == null) dao.insertSettings(value) else if (dao.updateSettings(value) != 1)
                throw DomainException(ErrorCode.STALE_RECORD)
            if (change is SaveLanguage) {
                val english = change.language == AppLanguage.ENGLISH ||
                    (change.language == AppLanguage.SYSTEM && java.util.Locale.getDefault().language != "zh")
                dao.localizeAccountNames(english)
                dao.localizeAccountNotes(english)
                dao.localizeCashNames(english)
                dao.localizeTypes(english)
                dao.localizeInstruments(english)
            }
            if (change is SaveFinancialSettings) {
                dao.clearRates()
                dao.insertRates(settings.rates.map { FxRateEntity(it.sourceCurrency.code, it.targetCurrency.code,
                    it.rate.stripTrailingZeros().toPlainString(), clock.millis()) })
                val state = db.statistics().state()
                if (state != null) {
                    val day = java.time.Instant.ofEpochMilli(state.baseline_at_ms)
                        .atZone(clock.zone).toLocalDate().toEpochDay()
                    db.statistics().invalidate(day)
                }
            }
            audit.recordSetting(change, auditBefore, audit.settingsJson(), clock.millis())
            settings.copy(revision = value.revision)
        }
    }
}
