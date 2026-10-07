package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Preserve legacy connection identity, but invalidate every old worker and remote write. */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cloud_backup_state ADD COLUMN provider TEXT NOT NULL DEFAULT 'GOOGLE_DRIVE'")
        db.execSQL("ALTER TABLE cloud_backup_attempts ADD COLUMN provider TEXT NOT NULL DEFAULT 'GOOGLE_DRIVE'")
        db.execSQL("""UPDATE cloud_backup_state SET automatic_enabled=0,
            connection_generation=connection_generation+1, schedule_generation=schedule_generation+1,
            next_due_at_utc_ms=NULL, scheduled_cycle_id=NULL, attempt_state='CANCELLED',
            latest_error=NULL, pending_banner_event_id=NULL, pending_banner_error=NULL""")
        db.execSQL("""UPDATE cloud_backup_attempts SET state='CANCELLED', error='SESSION_EXPIRED'
            WHERE state IN ('PREPARING','UPLOADING','VERIFYING','UNKNOWN_RESULT')""")
    }
}
