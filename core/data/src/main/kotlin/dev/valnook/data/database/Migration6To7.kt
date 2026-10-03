package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds source history and derived-cache metadata without rewriting any v6 business row. */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE app_settings ADD COLUMN navigation_order TEXT NOT NULL DEFAULT 'ACCOUNTS,INVESTMENTS,STATISTICS,SETTINGS'")
        db.execSQL("ALTER TABLE app_settings ADD COLUMN navigation_visible TEXT NOT NULL DEFAULT 'ACCOUNTS,INVESTMENTS,STATISTICS,SETTINGS'")
        db.execSQL("""CREATE TABLE IF NOT EXISTS instrument_price_history (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            instrument_id INTEGER NOT NULL,
            price_e5 INTEGER NOT NULL,
            currency_code TEXT NOT NULL,
            effective_at_ms INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,
            updated_at_ms INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            is_deleted INTEGER NOT NULL,
            FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instrument_price_history_instrument_id_is_deleted_effective_at_ms_id ON instrument_price_history(instrument_id,is_deleted,effective_at_ms,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instrument_price_history_currency_code ON instrument_price_history(currency_code)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS statistics_state (
            id INTEGER NOT NULL PRIMARY KEY,
            source_revision INTEGER NOT NULL,
            rule_version INTEGER NOT NULL,
            baseline_at_ms INTEGER NOT NULL,
            earliest_invalidated_epoch_day INTEGER)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS statistics_baseline_items (
            item_kind TEXT NOT NULL,
            reference_id INTEGER NOT NULL,
            account_id INTEGER,
            instrument_id INTEGER,
            currency_code TEXT NOT NULL,
            amount_long INTEGER NOT NULL,
            secondary_long INTEGER,
            PRIMARY KEY(item_kind,reference_id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS statistics_cache (
            metric TEXT NOT NULL,
            epoch_day INTEGER NOT NULL,
            value_decimal TEXT,
            reliable INTEGER NOT NULL,
            source_revision INTEGER NOT NULL,
            rule_version INTEGER NOT NULL,
            computed_at_ms INTEGER NOT NULL,
            PRIMARY KEY(metric,epoch_day))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_statistics_cache_source_revision_epoch_day ON statistics_cache(source_revision,epoch_day)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS demo_labels (
            entity_kind TEXT NOT NULL,
            entity_id INTEGER NOT NULL,
            field_name TEXT NOT NULL,
            zh_hans TEXT NOT NULL,
            english TEXT NOT NULL,
            PRIMARY KEY(entity_kind,entity_id,field_name))""")
        val now = System.currentTimeMillis()
        val epochDay = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
        db.execSQL("INSERT OR IGNORE INTO statistics_state(id,source_revision,rule_version,baseline_at_ms,earliest_invalidated_epoch_day) VALUES (1,1,$STATISTICS_RULE_VERSION,?,?)", arrayOf(now, epochDay))
        db.execSQL("""INSERT INTO statistics_baseline_items
            SELECT 'CASH',id,savings_account_id,NULL,currency_code,balance_minor,NULL FROM cash_accounts""")
        db.execSQL("""INSERT INTO statistics_baseline_items
            SELECT 'DEPOSIT',id,savings_account_id,NULL,currency_code,principal_minor,NULL FROM term_deposits WHERE status='OPEN'""")
        db.execSQL("""INSERT INTO statistics_baseline_items
            SELECT 'POSITION',p.id,p.savings_account_id,p.instrument_id,s.currency_code,p.holding_quantity_e8,s.current_price_e5
            FROM investments p JOIN instruments s ON s.id=p.instrument_id""")
        db.execSQL("""INSERT INTO instrument_price_history(instrument_id,price_e5,currency_code,effective_at_ms,created_at_ms,updated_at_ms,revision,is_deleted)
            SELECT id,current_price_e5,currency_code,?,?,?,1,0 FROM instruments""", arrayOf(now, now, now))
    }
}
