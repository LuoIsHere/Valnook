package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds optional credit metadata. Existing cash accounts remain SAVINGS because no profile is backfilled. */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS credit_account_profiles (
            account_id INTEGER NOT NULL PRIMARY KEY,
            credit_limit_minor INTEGER,
            statement_day INTEGER NOT NULL,
            due_rule_type TEXT NOT NULL,
            due_rule_value INTEGER NOT NULL,
            limit_source_account_id INTEGER,
            FOREIGN KEY(account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(limit_source_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_credit_account_profiles_limit_source_account_id ON credit_account_profiles(limit_source_account_id)")
    }
}
