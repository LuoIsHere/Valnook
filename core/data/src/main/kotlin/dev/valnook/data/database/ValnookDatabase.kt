package dev.valnook.data.database

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.Currency

@Database(entities = [AccountEntity::class,CurrencyEntity::class,CashEntity::class,DepositEntity::class,
    TypeEntity::class,InstrumentEntity::class,InvestmentEntity::class,TradeEntity::class,OperationEntity::class,MovementEntity::class,CashEntryEntity::class,
    SettingsEntity::class,FxRateEntity::class],
    version = 6, exportSchema = true)
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
    companion object {
        val seed = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                Currency.supported.forEach {
                    db.execSQL("INSERT INTO currencies(code,fraction_digits) VALUES (?,?)", arrayOf<Any>(it.code,it.fraction_digits))
                }
            }
        }
        fun open(context: Context): ValnookDatabase =
            Room.databaseBuilder(context,ValnookDatabase::class.java,"valnook.db")
                .addCallback(seed).addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
        fun inMemory(context: Context): ValnookDatabase =
            Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(seed).build()
    }
}
