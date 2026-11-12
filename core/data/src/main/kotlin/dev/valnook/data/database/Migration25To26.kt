package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Bills without an account (1901, second build): an expense or income may be kept on no account at all. A marker tells
 * those apart from bills whose account was deleted; before this schema an empty account only came from a deletion.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE bill_entries ADD COLUMN account_deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE bill_entries SET account_deleted=1 WHERE type<>'TRANSFER' AND cash_account_id IS NULL")
    }
}
