package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Removes account photos no account shows: before schema 24, changing an account's icon kept the old photo,
 * and every backup carried it along. Changing or deleting an icon now removes it right away.
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(DELETE_UNSHOWN_ACCOUNT_ICON_IMAGES)
    }
}

/** Also run after a restore, as a backup made before schema 24 may still hold such photos. */
internal const val DELETE_UNSHOWN_ACCOUNT_ICON_IMAGES = """DELETE FROM account_icon_images WHERE id NOT IN
    (SELECT icon_value FROM savings_accounts WHERE icon_type='IMAGE')"""
