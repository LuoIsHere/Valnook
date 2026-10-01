package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.Currency

/** Preserves v1 balances, receipts and source records; never rebuilds user data by clearing it. */
val MIGRATION_1_2 = object : Migration(1,2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE term_deposits ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE investment_trades ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE investment_trades ADD COLUMN is_deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE investment_trades ADD COLUMN updated_at_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE investment_trades SET updated_at_ms=created_at_ms")
        db.execSQL("DROP INDEX index_investment_trades_investment_id_occurred_at_ms_id")
        db.execSQL("CREATE INDEX index_investment_trades_investment_id_is_deleted_occurred_at_ms_id ON investment_trades(investment_id,is_deleted,occurred_at_ms DESC,id DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cash_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            original_operation_id TEXT NOT NULL,
            savings_account_id INTEGER NOT NULL,
            currency_code TEXT NOT NULL,
            source_kind TEXT NOT NULL,
            source_id INTEGER,
            delta_minor INTEGER NOT NULL,
            occurred_at_ms INTEGER NOT NULL,
            note TEXT NOT NULL,
            revision INTEGER NOT NULL,
            is_deleted INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,
            updated_at_ms INTEGER NOT NULL,
            FOREIGN KEY(savings_account_id,currency_code) REFERENCES cash_balances(savings_account_id,currency_code) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(original_operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""")
        db.execSQL("CREATE UNIQUE INDEX index_cash_entries_original_operation_id ON cash_entries(original_operation_id)")
        db.execSQL("CREATE UNIQUE INDEX index_cash_entries_source_kind_source_id ON cash_entries(source_kind,source_id)")
        db.execSQL("CREATE INDEX index_cash_entries_savings_account_id_currency_code_is_deleted_occurred_at_ms_id ON cash_entries(savings_account_id,currency_code,is_deleted,occurred_at_ms DESC,id DESC)")
        db.execSQL("""INSERT INTO cash_entries(id,original_operation_id,savings_account_id,currency_code,
            source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms)
            SELECT m.id,m.operation_id,m.savings_account_id,m.currency_code,
                CASE WHEN t.id IS NOT NULL THEN 'TRADE' WHEN opened.id IS NOT NULL THEN 'TERM_OPEN'
                     WHEN closed.id IS NOT NULL THEN 'TERM_CLOSE' ELSE 'CASH_SET' END,
                COALESCE(t.id,opened.id,closed.id),m.delta_minor,COALESCE(t.occurred_at_ms,m.created_at_ms),
                '',1,0,m.created_at_ms,m.created_at_ms
            FROM cash_movements m
            LEFT JOIN investment_trades t ON t.operation_id=m.operation_id
            LEFT JOIN term_deposits opened ON opened.open_operation_id=m.operation_id
            LEFT JOIN term_deposits closed ON closed.close_operation_id=m.operation_id""")
        Currency.supported.forEach {
            db.execSQL("INSERT OR IGNORE INTO currencies(code,fraction_digits) VALUES (?,?)",arrayOf<Any>(it.code,it.fraction_digits))
        }
    }
}
