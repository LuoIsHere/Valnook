package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Credit cards: whether the statement day's own spending goes on that day's statement; on, as cycles were read. */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `credit_account_profiles` ADD COLUMN `statement_day_included` INTEGER NOT NULL DEFAULT 1")
    }
}
