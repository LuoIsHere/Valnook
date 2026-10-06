package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN icon_type TEXT NOT NULL DEFAULT 'SYMBOL'")
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN icon_value TEXT NOT NULL DEFAULT 'account_balance'")
        db.execSQL("CREATE TABLE IF NOT EXISTS account_icon_images (id TEXT NOT NULL PRIMARY KEY, data BLOB NOT NULL)")
    }
}
