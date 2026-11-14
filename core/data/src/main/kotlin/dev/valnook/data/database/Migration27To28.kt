package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** AI settings: one optional row; without it the defaults apply. */
val MIGRATION_27_28 = object : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `ai_settings` (`id` INTEGER NOT NULL, `preset_id` TEXT,
            `base_url` TEXT NOT NULL DEFAULT '', `model` TEXT NOT NULL DEFAULT '', `extra_params` TEXT NOT NULL DEFAULT '',
            `thinking_off_params` TEXT NOT NULL DEFAULT '', `thinking_on_params` TEXT NOT NULL DEFAULT '',
            `enabled` INTEGER NOT NULL DEFAULT 0, `bill_chat` INTEGER NOT NULL DEFAULT 1, `share_text` INTEGER NOT NULL DEFAULT 1,
            `share_image` INTEGER NOT NULL DEFAULT 0, `query_enabled` INTEGER NOT NULL DEFAULT 0,
            `thinking` INTEGER NOT NULL DEFAULT 0, `chat` INTEGER NOT NULL DEFAULT 0, `send_habits` INTEGER NOT NULL DEFAULT 0,
            `default_time_minutes` INTEGER NOT NULL DEFAULT 720, `default_cash_account_id` INTEGER,
            `default_expense_category_id` INTEGER, `default_income_category_id` INTEGER,
            `my_rules` TEXT NOT NULL DEFAULT '', `user_nickname` TEXT NOT NULL DEFAULT '', `ai_nickname` TEXT NOT NULL DEFAULT '',
            `revision` INTEGER NOT NULL DEFAULT 0, `updated_at_ms` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))""")
    }
}
