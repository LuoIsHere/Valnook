package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Freeze the previous display order before introducing user-defined ordering. */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cash_accounts ADD COLUMN display_order INTEGER NOT NULL DEFAULT 0")
        initializeAccountDisplayOrder(db)
    }
}

internal fun initializeAccountDisplayOrder(db: SupportSQLiteDatabase) {
    db.execSQL("""UPDATE savings_accounts SET display_order=(SELECT COUNT(*) FROM savings_accounts a
        WHERE a.id<savings_accounts.id)""")
    db.execSQL("""UPDATE cash_accounts SET display_order=(SELECT COUNT(*) FROM cash_accounts c
        WHERE c.savings_account_id=cash_accounts.savings_account_id AND
        (c.name<cash_accounts.name OR (c.name=cash_accounts.name AND c.id<cash_accounts.id)))""")
}
