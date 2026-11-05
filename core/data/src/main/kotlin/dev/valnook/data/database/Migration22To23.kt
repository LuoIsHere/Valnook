package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the device-local copy of the cloud backup list. It starts empty: the list has not been fetched on this version yet. */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS cloud_backup_index (file_id TEXT NOT NULL, provider TEXT NOT NULL,
            account_reference TEXT NOT NULL, folder_id TEXT NOT NULL, position INTEGER NOT NULL, file_name TEXT NOT NULL,
            snapshot_created_at_utc TEXT NOT NULL, drive_created_at_utc TEXT NOT NULL, byte_count INTEGER NOT NULL,
            app_version TEXT NOT NULL, internal_build_revision TEXT NOT NULL, database_schema_version INTEGER NOT NULL,
            format_version INTEGER NOT NULL, data_schema_version INTEGER NOT NULL, verification_state TEXT NOT NULL,
            PRIMARY KEY(file_id))""")
        db.execSQL("ALTER TABLE cloud_backup_state ADD COLUMN backups_refreshed_at_utc_ms INTEGER")
    }
}
