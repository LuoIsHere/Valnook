package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN show_deposit_summary INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN show_investment_summary INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE cash_accounts ADD COLUMN include_in_available_cash INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE cash_accounts ADD COLUMN show_on_accounts_page INTEGER NOT NULL DEFAULT 1")
    }
}
