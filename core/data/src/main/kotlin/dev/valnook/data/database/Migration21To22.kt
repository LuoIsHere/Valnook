package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the exchange rate history and the device-local switch for automatic rates. Existing installs start with
 * the switch off and an empty history, so their rates and totals stay exactly as they were.
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS fx_rate_history (base_currency TEXT NOT NULL, quote_currency TEXT NOT NULL,
            local_day INTEGER NOT NULL, source_date INTEGER NOT NULL, rate TEXT NOT NULL, fetched_at_ms INTEGER NOT NULL,
            PRIMARY KEY(base_currency, quote_currency, local_day),
            FOREIGN KEY(base_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(quote_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_fx_rate_history_quote_currency ON fx_rate_history (quote_currency)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS fx_local_state (id INTEGER NOT NULL, auto_enabled INTEGER NOT NULL DEFAULT 0,
            notice_dismissed INTEGER NOT NULL DEFAULT 0, last_success_day INTEGER, last_success_at_ms INTEGER,
            last_attempt_at_ms INTEGER, last_error TEXT, data_date INTEGER, PRIMARY KEY(id))""")
        insertDefaultFxLocalState(db)
        db.execSQL("ALTER TABLE statistics_cache ADD COLUMN complete INTEGER NOT NULL DEFAULT 1")
    }
}

/** Shared by database creation and this migration: automatic rates off, notice not dismissed, never synced. */
internal fun insertDefaultFxLocalState(db: SupportSQLiteDatabase) {
    db.execSQL("INSERT OR IGNORE INTO fx_local_state(id,auto_enabled,notice_dismissed) VALUES (1,0,0)")
}
