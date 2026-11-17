package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** AI settings: whether new draft cards keep their pictures on the bill; on, as cards always started before. */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `ai_settings` ADD COLUMN `default_keep_images` INTEGER NOT NULL DEFAULT 1")
    }
}
