package dev.valnook.data.database

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.Currency

const val DATABASE_SCHEMA_VERSION = 25

@Database(entities = [WalletPrivateIdentity::class,WalletCardEntity::class,WalletImageEntity::class,AccountIconImageEntity::class,AccountGroupEntity::class,AccountEntity::class,CurrencyEntity::class,CashEntity::class,DepositEntity::class,
    CreditAccountProfileEntity::class,
    TypeEntity::class,InstrumentEntity::class,InvestmentEntity::class,TradeEntity::class,OperationEntity::class,MovementEntity::class,CashEntryEntity::class,
    SettingsEntity::class,FxRateEntity::class,InstrumentPriceEntity::class,StatisticsStateEntity::class,
    StatisticsBaselineItemEntity::class,StatisticsCacheEntity::class,DemoLabelEntity::class,
    AuditEventEntity::class,AuditEventAccountEntity::class,AuditMetadataEntity::class,
    LocalMaintenanceStateEntity::class,CloudBackupStateEntity::class,CloudBackupAttemptEntity::class,CloudBackupIndexEntity::class,
    FxRateHistoryEntity::class,FxLocalStateEntity::class,BillCategoryEntity::class,BillEntryEntity::class],
    version = DATABASE_SCHEMA_VERSION, exportSchema = true)
abstract class ValnookDatabase : RoomDatabase() {
    abstract fun walletPrivateIdentity(): WalletPrivateIdentityDao
    abstract fun wallet(): WalletDao
    abstract fun ledger(): LedgerDao
    abstract fun accounts(): AccountDao
    abstract fun accountGroups(): AccountGroupDao
    abstract fun cash(): CashDao
    abstract fun credit(): CreditDao
    abstract fun deposits(): DepositDao
    abstract fun positions(): PositionDao
    abstract fun trades(): TradeDao
    abstract fun instruments(): InstrumentDao
    abstract fun operations(): OperationDao
    abstract fun overview(): OverviewDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun statistics(): StatisticsDao
    abstract fun audit(): AuditDao
    abstract fun cloudBackup(): CloudBackupDao
    abstract fun fx(): FxDao
    abstract fun bills(): BillDao
    companion object {
        val seed = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                Currency.supported.forEach {
                    db.execSQL("INSERT INTO currencies(code,fraction_digits) VALUES (?,?)", arrayOf<Any>(it.code,it.fraction_digits))
                }
                val now = System.currentTimeMillis()
                val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toEpochDay()
                insertDefaultAccountGroup(db, now)
                insertDefaultFxLocalState(db)
                insertDefaultBillCategories(db, now)
                db.execSQL("INSERT INTO statistics_state(id,source_revision,rule_version,baseline_at_ms,earliest_invalidated_epoch_day) VALUES (1,1,$STATISTICS_RULE_VERSION,?,?)",
                    arrayOf(now, day))
                db.execSQL("INSERT INTO audit_metadata(id,protocol_version,tracking_start_ms,tracking_start_database_version,complete_since_start,legacy_history_before_start) VALUES (1,1,?,9,1,0)", arrayOf(now))
                db.execSQL("INSERT INTO local_maintenance_state(id,dataset_generation,maintenance_in_progress,last_restore_attempt_id,last_restore_backup_sha256,last_restore_committed_at_ms,upload_pause_reason,updated_at_ms) VALUES (1,1,0,NULL,NULL,NULL,NULL,?)", arrayOf(now))
                db.execSQL("INSERT INTO cloud_backup_state(id,account_reference,account_display,folder_id,connection_generation,automatic_enabled,interval_hours,pause_reason,schedule_generation,next_due_at_utc_ms,scheduled_cycle_id,attempt_state,latest_error,last_attempt_at_utc_ms,last_success_at_utc_ms,cleanup_incomplete,pending_banner_event_id,pending_banner_error,observed_restore_attempt_id,updated_at_ms) VALUES (1,NULL,NULL,NULL,1,0,24,'NONE',1,NULL,NULL,'IDLE',NULL,NULL,NULL,0,NULL,NULL,NULL,?)", arrayOf(now))
            }
        }
        /**
         * Every opening, after creation and migrations: databases from before the default class get it here, and a
         * restore cut short by a crash or a kill no longer holds the maintenance flag, as nothing restores yet.
         */
        val defaults = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                ensureDefaultAssetType(db, System.currentTimeMillis())
                db.execSQL("UPDATE local_maintenance_state SET maintenance_in_progress=0 WHERE maintenance_in_progress<>0")
            }
        }
        /** Every migration, oldest first; the real and the demo database open with the same list. */
        private val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25)
        fun open(context: Context): ValnookDatabase =
            Room.databaseBuilder(context,ValnookDatabase::class.java,"valnook.db")
                .addCallback(seed).addCallback(defaults).addMigrations(*MIGRATIONS)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        fun fromAsset(context: Context, name: String, assetPath: String): ValnookDatabase =
            Room.databaseBuilder(context,ValnookDatabase::class.java,name)
                .createFromAsset(assetPath).addCallback(defaults)
                .addMigrations(*MIGRATIONS)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        fun inMemory(context: Context): ValnookDatabase =
            Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(seed).addCallback(defaults).build()

        fun staging(context: Context, name: String): ValnookDatabase =
            Room.databaseBuilder(context, ValnookDatabase::class.java, name)
                .setJournalMode(JournalMode.TRUNCATE).build()
    }
}
