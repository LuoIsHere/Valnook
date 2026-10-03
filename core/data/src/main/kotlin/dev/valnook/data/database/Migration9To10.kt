package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds device-local cloud connection, schedule and attempt state without changing portable data. */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS cloud_backup_state (
            id INTEGER NOT NULL PRIMARY KEY,
            account_reference TEXT,
            account_display TEXT,
            folder_id TEXT,
            connection_generation INTEGER NOT NULL,
            automatic_enabled INTEGER NOT NULL,
            interval_hours INTEGER NOT NULL,
            pause_reason TEXT NOT NULL,
            schedule_generation INTEGER NOT NULL,
            next_due_at_utc_ms INTEGER,
            scheduled_cycle_id TEXT,
            attempt_state TEXT NOT NULL,
            latest_error TEXT,
            last_attempt_at_utc_ms INTEGER,
            last_success_at_utc_ms INTEGER,
            cleanup_incomplete INTEGER NOT NULL,
            pending_banner_event_id TEXT,
            pending_banner_error TEXT,
            observed_restore_attempt_id TEXT,
            updated_at_ms INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cloud_backup_attempts (
            attempt_id TEXT NOT NULL PRIMARY KEY,
            cycle_id TEXT NOT NULL,
            backup_id TEXT NOT NULL,
            data_generation INTEGER NOT NULL,
            connection_generation INTEGER NOT NULL,
            schedule_generation INTEGER NOT NULL,
            folder_id TEXT NOT NULL,
            planned_drive_file_id TEXT,
            drive_file_id TEXT,
            local_archive_path TEXT NOT NULL,
            archive_sha256 TEXT NOT NULL,
            archive_md5 TEXT NOT NULL,
            archive_size INTEGER NOT NULL,
            started_at_utc_ms INTEGER NOT NULL,
            finished_at_utc_ms INTEGER,
            state TEXT NOT NULL,
            error TEXT
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cloud_backup_attempts_cycle_id ON cloud_backup_attempts(cycle_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cloud_backup_attempts_started_at_utc_ms_attempt_id ON cloud_backup_attempts(started_at_utc_ms,attempt_id)")
        val now = System.currentTimeMillis()
        db.execSQL("""INSERT INTO cloud_backup_state(id,account_reference,account_display,folder_id,
            connection_generation,automatic_enabled,interval_hours,pause_reason,schedule_generation,
            next_due_at_utc_ms,scheduled_cycle_id,attempt_state,latest_error,last_attempt_at_utc_ms,
            last_success_at_utc_ms,cleanup_incomplete,pending_banner_event_id,pending_banner_error,
            observed_restore_attempt_id,updated_at_ms)
            VALUES(1,NULL,NULL,NULL,1,0,24,'NONE',1,NULL,NULL,'IDLE',NULL,NULL,NULL,0,NULL,NULL,NULL,?)""",
            arrayOf(now))
    }
}
