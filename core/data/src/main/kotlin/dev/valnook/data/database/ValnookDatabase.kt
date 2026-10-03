package dev.valnook.data.database

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.Currency

@Database(entities = [AccountEntity::class,CurrencyEntity::class,CashEntity::class,DepositEntity::class,
    TypeEntity::class,InstrumentEntity::class,InvestmentEntity::class,TradeEntity::class,OperationEntity::class,MovementEntity::class,CashEntryEntity::class,
    SettingsEntity::class,FxRateEntity::class,InstrumentPriceEntity::class,StatisticsStateEntity::class,
    StatisticsBaselineItemEntity::class,StatisticsCacheEntity::class,DemoLabelEntity::class,
    AuditEventEntity::class,AuditEventAccountEntity::class,AuditMetadataEntity::class,
    LocalMaintenanceStateEntity::class],
    version = 9, exportSchema = true)
abstract class ValnookDatabase : RoomDatabase() {
    abstract fun ledger(): LedgerDao
    abstract fun accounts(): AccountDao
    abstract fun cash(): CashDao
    abstract fun deposits(): DepositDao
    abstract fun positions(): PositionDao
    abstract fun trades(): TradeDao
    abstract fun instruments(): InstrumentDao
    abstract fun operations(): OperationDao
    abstract fun overview(): OverviewDao
    abstract fun maintenance(): MaintenanceDao
    abstract fun statistics(): StatisticsDao
    abstract fun audit(): AuditDao
    companion object {
        val seed = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                Currency.supported.forEach {
                    db.execSQL("INSERT INTO currencies(code,fraction_digits) VALUES (?,?)", arrayOf<Any>(it.code,it.fraction_digits))
                }
                val now = System.currentTimeMillis()
                val day = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toEpochDay()
                db.execSQL("INSERT INTO statistics_state(id,source_revision,rule_version,baseline_at_ms,earliest_invalidated_epoch_day) VALUES (1,1,$STATISTICS_RULE_VERSION,?,?)",
                    arrayOf(now, day))
                db.execSQL("INSERT INTO audit_metadata(id,protocol_version,tracking_start_ms,tracking_start_database_version,complete_since_start,legacy_history_before_start) VALUES (1,1,?,9,1,0)", arrayOf(now))
                db.execSQL("INSERT INTO local_maintenance_state(id,dataset_generation,maintenance_in_progress,last_restore_attempt_id,last_restore_backup_sha256,last_restore_committed_at_ms,upload_pause_reason,updated_at_ms) VALUES (1,1,0,NULL,NULL,NULL,NULL,?)", arrayOf(now))
            }
        }
        fun open(context: Context): ValnookDatabase =
            Room.databaseBuilder(context,ValnookDatabase::class.java,"valnook.db")
                .addCallback(seed).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        fun fromAsset(context: Context, name: String, assetPath: String): ValnookDatabase =
            Room.databaseBuilder(context,ValnookDatabase::class.java,name)
                .createFromAsset(assetPath)
                .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        fun inMemory(context: Context): ValnookDatabase =
            Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(seed).build()

        fun staging(context: Context, name: String): ValnookDatabase =
            Room.databaseBuilder(context, ValnookDatabase::class.java, name)
                .setJournalMode(JournalMode.TRUNCATE).build()
    }
}
