package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Introduces account groups. Every existing account joins the default group and keeps its display order,
 * so the accounts page looks unchanged until the user creates a group.
 */
val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS account_groups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL, display_order INTEGER NOT NULL, collapsed INTEGER NOT NULL DEFAULT 0,
            created_at_ms INTEGER NOT NULL, updated_at_ms INTEGER NOT NULL)""")
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN group_id INTEGER NOT NULL DEFAULT ${AccountGroupEntity.DEFAULT_ID}")
        insertDefaultAccountGroup(db, System.currentTimeMillis())
    }
}

/** Shared by database creation, this migration and restores of archives written before groups existed. */
internal fun insertDefaultAccountGroup(db: SupportSQLiteDatabase, nowMs: Long) {
    db.execSQL("""INSERT OR IGNORE INTO account_groups(id,name,display_order,collapsed,created_at_ms,updated_at_ms)
        VALUES (${AccountGroupEntity.DEFAULT_ID},'',0,0,?,?)""", arrayOf(nowMs, nowMs))
}
